package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises the actual native ingress handlers and NanoHTTPD final write notification. */
public final class Audit53NativeLifecycleTest {
    static int checks;static final List<String> failures=new ArrayList<>();
    static void check(boolean ok,String label){checks++;if(!ok){failures.add(label);System.out.println("FAIL "+label);}}
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static String json(){return "{\"id\":\"synthetic-response\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";}
    static String sse(){return "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"synthetic-response\",\"status\":\"in_progress\",\"vendor_extension\":\"preserve-me\"}}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":"+json()+"}\n\n";}
    static final class Conn extends HttpURLConnection {
        final InputStream input;final int code;final String mime;final AtomicInteger disconnects=new AtomicInteger();final AtomicBoolean closed=new AtomicBoolean();
        final ByteArrayOutputStream request=new ByteArrayOutputStream();RequestContext context;boolean failSend;
        Conn(String content,boolean stream,int status)throws Exception{this(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),stream,status);}
        Conn(InputStream data,boolean stream,int status)throws Exception{
            super(new URL("http://127.0.0.1/synthetic"));code=status;mime=stream?"text/event-stream":"application/json";
            input=new FilterInputStream(data){public void close()throws IOException{closed.set(true);super.close();}};
        }
        public void connect(){}public boolean usingProxy(){return false;}
        public void disconnect(){disconnects.incrementAndGet();try{input.close();}catch(IOException ignored){}}
        public OutputStream getOutputStream()throws IOException{if(failSend)throw new IOException("synthetic transport send failure");return request;}
        public int getResponseCode(){return code;}
        public String getHeaderField(String name){if("content-type".equalsIgnoreCase(name))return mime;if("retry-after".equalsIgnoreCase(name))return "3";return null;}
        public String getContentType(){return mime;}
        public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return input;}
    }
    static GatewayServer gateway(String provider,Conn conn,Path dir)throws Exception{
        JSONObject account=new JSONObject().put("id","synthetic-account").put("enabled",true).put("access","SYNTHETIC_ACCESS").put("token","SYNTHETIC_ACCESS")
                .put("refresh","SYNTHETIC_REFRESH").put("email","fixture@example.invalid").put("expires",System.currentTimeMillis()+3600000L);
        JSONObject p=new JSONObject().put("id",provider).put("protocol",provider.equals("codex")?"codex":"copilot").put("baseUrl","https://synthetic.invalid")
                .put("models",new JSONArray().put("m")).put("accounts",new JSONArray().put(account));
        GatewayServer g=RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","SYNTHETIC_INFERENCE"))
                .put("providers",new JSONArray().put(p)).put("privacy",new JSONObject().put("enabled",false))
                .put("guard",new JSONObject().put("enabled",true).put("maxOutputTokens",provider.equals("codex")?0:12)));
        field(GatewayServer.class,"configFile").set(g,dir.resolve("config-"+provider+".json").toFile());
        g.setVendorConnectionsForTests(url->{try{conn.context=(RequestContext)((ThreadLocal<?>)field(GatewayServer.class,"REQUEST_CONTEXT").get(null)).get();}catch(Exception e){throw new IOException(e);}return conn;});
        return g;
    }
    static NanoHTTPD.Response response(GatewayServer g,String provider,boolean stream)throws Exception{
        String path=provider.equals("codex")?(stream?"/backend-api/codex/responses":"/backend-api/codex/responses/compact"):"/responses";
        return g.serve(Audit53RegressionTest.session(path,"POST",new JSONObject().put("model",provider.equals("codex")?"m":"github-copilot/m")
                .put("input","synthetic test").put("stream",stream).toString(),"127.0.0.1",Collections.emptyMap()));
    }
    static void send(NanoHTTPD.Response r,boolean failFlush)throws Exception{
        r.setRequestMethod(NanoHTTPD.Method.POST);
        Method m=AllInTrackedResponse.class.getDeclaredMethod("send",OutputStream.class);m.setAccessible(true);
        m.invoke(r,new ByteArrayOutputStream(){public void flush()throws IOException{if(failFlush)throw new IOException("synthetic downstream flush failure");}});
    }
    static int connections(RequestContext ctx)throws Exception{return ctx==null?-1:((Set<?>)field(RequestContext.class,"connections").get(ctx)).size();}
    static void cases(Path dir)throws Exception{
        for(String provider:new String[]{"codex","github-copilot"})for(String mode:new String[]{"cancel","complete","flush-failure","json","reject","send-failure"}){
            List<UsageLedger.Row> rows=new ArrayList<>();UsageLedger.setHooks(rows::add);
            boolean stream=!mode.equals("json"),bad=mode.equals("reject");Conn conn=new Conn(bad?"{\"error\":{\"message\":\"synthetic refused\"}}":stream?sse():json(),stream,bad?429:200);conn.failSend=mode.equals("send-failure");
            GatewayServer g=gateway(provider,conn,dir);NanoHTTPD.Response r=response(g,provider,stream);String label=provider+" "+mode;
            try{
                if(mode.equals("cancel")){r.getData().read();r.close();}
                else {check(rows.isEmpty()||bad||mode.equals("send-failure"),label+" no success row before writer");send(r,mode.equals("flush-failure"));r.close();}
                check(conn.disconnects.get()>0,label+" upstream connection disconnected");check(conn.closed.get(),label+" body closed");
                check(rows.size()==1,label+" exactly one terminal usage row");
                if(rows.size()==1){UsageLedger.Row row=rows.get(0);boolean failure=mode.equals("cancel")||mode.equals("flush-failure")||bad||mode.equals("send-failure");
                    check(row.isTerminal()&&!row.isLegacy()&&row.ms>=0&&row.accountId.equals("synthetic-account"),label+" attributed nonlegacy terminal record");
                    check(failure?row.status>=400:row.status==200,label+" accurate terminal status");
                    if(mode.equals("cancel"))check(row.status==499,label+" cancellation distinct from upstream failure");
                    if(!failure&&provider.equals("github-copilot"))check(row.inputKnown&&row.outputKnown&&row.in==3&&row.out==2,label+" reported counters retained");
                    if(mode.equals("flush-failure"))check(row.diagnostics!=null&&!row.diagnostics.json().optBoolean("deliveryConfirmed",true),label+" downstream write not marked delivered");
                }
                check(conn.context!=null&&connections(conn.context)==0,label+" request connection registration released");check(g.activeForwardRequests()==0,label+" gate released");
                if(mode.equals("json"))check(r.getMimeType().contains("application/json"),label+" compact/json MIME retained");
                if(bad)check(r.getStatus().getRequestStatus()==429&&"3".equals(r.getHeader("Retry-After")),label+" upstream status/retry retained");
            }finally{r.close();UsageLedger.setHooks(null);}
        }
    }
    static void copilotDetachBeforeContextClose(Path dir)throws Exception{
        Conn conn=new Conn(sse(),true,200);GatewayServer g=gateway("github-copilot",conn,dir);
        NanoHTTPD.Response r=response(g,"github-copilot",true);
        try{String text=ResponsesGatewayOfflineTest.read(r.getData());check(text.contains("preserve-me")&&text.contains("response.completed"),"M8 vendor fields and terminal event preserved");check(connections(conn.context)==0,"M8 detached as soon as native source released, not only context cancellation");}finally{r.close();}
    }
    static void genericStreamDelivery(Path dir)throws Exception{
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1);List<UsageLedger.Row> rows=new ArrayList<>();UsageLedger.setHooks(rows::add);
        UsageLedger.Row row=new UsageLedger.Row();row.requestId="synthetic-generic";row.prov="mock";
        @SuppressWarnings("unchecked") ThreadLocal<UsageLedger.Row> slot=(ThreadLocal<UsageLedger.Row>)field(GatewayServer.class,"U_ROW").get(null);
        slot.set(row);NanoHTTPD.Response raw;
        try{raw=(NanoHTTPD.Response)Audit53RegressionTest.call(g,"nativeResponsesStream",new Class[]{InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class},new ByteArrayInputStream(sse().getBytes(StandardCharsets.UTF_8)),new PrivacyMasker.Session(),null,null);}finally{slot.remove();}
        AllInTrackedResponse tracked=new AllInTrackedResponse(raw,()->{});
        try{send(tracked,true);tracked.close();check(rows.size()==1&&row.status>=400,"S2 shared native final flush cannot leave successful ledger row");}finally{tracked.close();UsageLedger.setHooks(null);}
    }
    static void lateTerminalFailure()throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(1);
        NanoHTTPD.Response response=(NanoHTTPD.Response)Audit53RegressionTest.call(g,"nativeResponsesStream",new Class[]{InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class},new ByteArrayInputStream(sse().getBytes(StandardCharsets.UTF_8)),new PrivacyMasker.Session(),null,null);
        InputStream body=response.getData();
        try {
            Audit53RegressionTest.call(body,"finish",new Class[]{int.class,String.class},499,"synthetic cancellation");
            Audit53RegressionTest.call(body,"finish",new Class[]{int.class,String.class},200,"");
            check(field(body.getClass(),"resultStatus").getInt(body)==499,"Native cancelled outcome cannot be overwritten by a late terminal frame");
        } finally {response.close();}
    }
    static void cancelledAfterEof()throws Exception {
        RequestContext context=new RequestContext("native-test",NetworkPolicy.capture(),10000);
        NativeResponseLifecycle lifecycle=NativeResponseLifecycle.begin("codex","m","m","",context);lifecycle.markSent();
        InputStream body=lifecycle.wrap(new ByteArrayInputStream(new byte[]{1,2,3}),null,200);
        AllInTrackedResponse.WriteCompletion completed=(AllInTrackedResponse.WriteCompletion)body;
        completed.onWriteStart();while(body.read()!=-1){}
        context.cancel("synthetic client cancellation before final flush");completed.onWriteComplete(true);body.close();
        check(lifecycle.row.status==499 && !RequestDiagnostics.json(lifecycle.row).optBoolean("deliveryConfirmed",true),"Native EOF before a confirmed cancellation is not final successful delivery");
    }
    static void realSocketCancellation(Path dir)throws Exception {
        for(String provider:new String[]{"codex","github-copilot"}) {
            try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) {
                CountDownLatch disconnected=new CountDownLatch(1);AtomicReference<Socket> active=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
                Thread peer=new Thread(()->{
                    try(Socket socket=server.accept()) {
                        active.set(socket);socket.setSoTimeout(3000);InputStream in=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int match=0,ch;
                        while(match<4 && (ch=in.read())!=-1){headers.write(ch);match=match==0&&ch==13?1:match==1&&ch==10?2:match==2&&ch==13?3:match==3&&ch==10?4:0;if(headers.size()>65536)throw new IOException("test header cap");}
                        int length=0;for(String line:headers.toString("UTF-8").split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                        for(int i=0;i<length;i++)if(in.read()<0)throw new EOFException();
                        String first="event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"first\"}\n\n";
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+first).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                        try{if(in.read()==-1)disconnected.countDown();}catch(SocketException reset){disconnected.countDown();}
                    }catch(Throwable t){error.set(t);}
                },"audit53-loopback-peer");peer.setDaemon(true);peer.start();
                NanoHTTPD.Response r=null;
                try {
                    Conn unused=new Conn("",true,200);GatewayServer g=gateway(provider,unused,dir);
                    g.setVendorConnectionsForTests(url->NetworkPolicy.openHttp("http://127.0.0.1:"+server.getLocalPort()+"/responses"));
                    r=response(g,provider,true);check(r.getStatus().getRequestStatus()==200 && r.getData().read()!=-1,"S2 real TCP first incremental event "+provider);
                    long start=System.nanoTime();r.close();
                    check(disconnected.await(2,TimeUnit.SECONDS),"S2 peer observes TCP disconnect on client cancel "+provider);
                    System.out.println("NATIVE_TCP_CANCEL "+provider+" elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                } finally {if(r!=null)r.close();Socket live=active.get();if(live!=null)live.close();server.close();peer.join(4000);}
                check(!peer.isAlive()&&error.get()==null,"S2 loopback peer exits cleanly "+provider);
            }
        }
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("audit53-native-");UsageLedger.init(dir.toFile());
        try{for(String name:new String[]{"cases","detach","generic","tcp"})try{if(name.equals("cases"))cases(dir);else if(name.equals("detach"))copilotDetachBeforeContextClose(dir);else if(name.equals("tcp"))realSocketCancellation(dir);else genericStreamDelivery(dir);}catch(Throwable e){failures.add(name+" "+e.getClass().getSimpleName());System.out.println("FAIL "+name+" "+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()));}
            lateTerminalFailure();cancelledAfterEof();
            System.out.println("Audit53NativeLifecycleTest checks="+checks+" failures="+failures.size());if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        }finally{UsageLedger.setHooks(null);GatewayStreamReadOfflineTest.closeLedger();try(java.util.stream.Stream<Path> s=Files.walk(dir)){for(Path p:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
}
