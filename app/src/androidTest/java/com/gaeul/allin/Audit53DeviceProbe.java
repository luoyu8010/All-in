package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Separate Android app_process, synthetic accounts only; no Activity or account directory access. */
public final class Audit53DeviceProbe {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    static Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
    static final String SSE="event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}\n\n";
    static class Conn extends HttpURLConnection {
        int disconnected;boolean closed;final int code;final byte[] bytes;
        Conn(int code,String body)throws Exception{super(new URL("http://127.0.0.1/synthetic"));this.code=code;bytes=body.getBytes(StandardCharsets.UTF_8);}
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected++;closed=true;}
        public int getResponseCode(){return code;}public String getContentType(){return "text/event-stream";}
        public String getHeaderField(String key){return "content-type".equalsIgnoreCase(key)?getContentType():null;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes){public void close(){closed=true;}};}
        public InputStream getErrorStream(){return getInputStream();}
    }
    static GatewayServer gateway(String provider,File root)throws Exception {
        JSONObject account=new JSONObject().put("id","synthetic-account").put("enabled",true).put("token","SYNTHETIC_TOKEN").put("access","SYNTHETIC_TOKEN").put("refresh","SYNTHETIC_REFRESH").put("expires",System.currentTimeMillis()+3600000);
        JSONObject p=new JSONObject().put("id",provider).put("baseUrl","https://synthetic.invalid").put("accounts",new JSONArray().put(account));
        GatewayServer g=Audit52DeviceProbe.gateway(new JSONObject().put("providers",new JSONArray().put(p)).put("guard",new JSONObject().put("enabled",false)).put("privacy",new JSONObject().put("enabled",false)));
        field(GatewayServer.class,"configFile").set(g,new File(root,"config.json"));field(GatewayServer.class,"totalMasked").set(g,new AtomicInteger());
        return g;
    }
    static void nativeBodies(File root)throws Exception {
        final List<UsageLedger.Row> rows=new ArrayList<>();UsageLedger.setHooks(rows::add);
        for(String provider:new String[]{"codex","github-copilot"})for(String mode:new String[]{"complete","cancel","flush-failure"}) {
            rows.clear();GatewayServer g=gateway(provider,root);Conn conn=new Conn(200,SSE);g.setVendorConnectionsForTests(url->conn);
            String body=new JSONObject().put("model",provider.equals("codex")?"m":"github-copilot/m").put("stream",true).put("input","synthetic").toString();
            NanoHTTPD.Response raw;
            if(provider.equals("codex"))raw=(NanoHTTPD.Response)call(g,"handleCodexNative",new Class[]{NanoHTTPD.IHTTPSession.class,String.class,String.class,Map.class,String.class},null,"/backend-api/codex/responses","POST",Collections.emptyMap(),body);
            else raw=(NanoHTTPD.Response)call(g,"handleCopilotResponses",new Class[]{String.class,Map.class,String.class},"/responses",Collections.emptyMap(),body);
            check(raw.getStatus().getRequestStatus()==200,provider+" synthetic native path reached");
            AllInTrackedResponse tracked=new AllInTrackedResponse(raw,()->{});tracked.setRequestMethod(NanoHTTPD.Method.POST);
            if(mode.equals("cancel")){raw.getData().read();tracked.close();}
            else {check(rows.isEmpty(),"no premature native row");call(tracked,"send",new Class[]{OutputStream.class},new ByteArrayOutputStream(){public void flush()throws IOException{if(mode.equals("flush-failure"))throw new IOException("synthetic downstream failure");}});tracked.close();}
            check(conn.disconnected>0&&conn.closed,provider+" connection closed on "+mode);
            check(rows.size()==1&&rows.get(0).status==(mode.equals("complete")?200:499),provider+" native terminal row "+mode);
            check(!rows.get(0).isLegacy()&&rows.get(0).accountId.equals("synthetic-account"),provider+" native attribution");
            if(provider.equals("github-copilot")&&mode.equals("complete"))check(rows.get(0).in==3&&rows.get(0).out==2,"native reported usage retained");
        }
        UsageLedger.setHooks(null);
    }
    static void dns()throws Exception {
        BoundedDns.Resolver old=BoundedDns.resolver;CountDownLatch entered=new CountDownLatch(4),release=new CountDownLatch(1);ExecutorService callers=Executors.newFixedThreadPool(4);List<Future<Boolean>> jobs=new ArrayList<>();
        try {
            BoundedDns.resolver=h->{entered.countDown();while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}return new InetAddress[]{InetAddress.getLoopbackAddress()};};
            for(int i=0;i<4;i++)jobs.add(callers.submit(()->{try{BoundedDns.resolve("synthetic.invalid",System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400));return false;}catch(SocketTimeoutException expected){return true;}}));
            check(entered.await(2,TimeUnit.SECONDS),"Android entered four platform DNS slots");for(Future<Boolean> job:jobs)check(job.get(2,TimeUnit.SECONDS),"Android DNS caller deadline");
            check(BoundedDns.active()==4&&BoundedDns.diagnostics().optBoolean("saturated"),"Android stuck DNS visible and bounded");
            boolean refused=false;try{BoundedDns.resolve("fifth.invalid",System.nanoTime()+TimeUnit.SECONDS.toNanos(1));}catch(IOException expected){refused=true;}
            check(refused&&BoundedDns.active()==4,"Android no replacement DNS threads");
        }finally{release.countDown();callers.shutdownNow();callers.awaitTermination(2,TimeUnit.SECONDS);BoundedDns.resolver=old;}
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(BoundedDns.active()>0&&System.nanoTime()<limit)Thread.yield();check(BoundedDns.active()==0,"Android slots released after platform returns");
    }
    static void basics()throws Exception {
        try(BoundedSseReader r=new BoundedSseReader(new StringReader("data: a\r\ndata: b\r\n\r\nlast\n"))){check(r.readEvent().data.equals("a\nb"),"Android multiline SSE");check(r.readLine().equals("last"),"Android mixed line/event");check(r.readEvent()==null&&BoundedSseReader.activeBytes()==0,"Android EOF releases budget");}
        AtomicBoolean closed=new AtomicBoolean();byte[] bytes="短读 UTF-8".getBytes(StandardCharsets.UTF_8);
        check(Arrays.equals(bytes,GatewayServer.readFileBytes(new ByteArrayInputStream(bytes){public int available(){return 0;}public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,1));}public void close(){closed.set(true);}}))&&closed.get(),"Android short asset reads close and preserve bytes");
        check("bigmodel".equals(GatewayServer.zcodeSiteForBase("https://open.bigmodel.cn"))&&"zai".equals(GatewayServer.zcodeSiteForBase(null)),"Android ZCode site selection");
        check(!GatewayServer.safeLogMessage("fixture",new IOException("SYNTHETIC_SECRET_SENTINEL")).contains("SYNTHETIC_SECRET_SENTINEL"),"Android exception message excluded from diagnostics");
        check(!GatewayServer.allowedAntigravityDiagnosticHost("10.0.0.1")&&GatewayServer.allowedAntigravityDiagnosticHost("cloudcode-pa.googleapis.com"),"Android diagnostic host boundary");
    }
    static void callback(File root)throws Exception {
        final String attack="<script>window.__audit53=1</script>";
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){protected URLConnection openConnection(URL u)throws IOException{try{return new Conn(400,attack);}catch(Exception e){throw new IOException(e);}}protected URLConnection openConnection(URL u,java.net.Proxy p)throws IOException{return openConnection(u);}});
        GatewayServer g=gateway("codex",root);Map<String,CodexAuth.Flow> flows=new java.util.concurrent.ConcurrentHashMap<>();CodexAuth.Flow f=new CodexAuth.Flow();f.flowId="synthetic-flow";f.state="synthetic-state";f.verifier="synthetic-verifier";f.createdAt=System.currentTimeMillis();flows.put(f.flowId,f);field(GatewayServer.class,"codexFlows").set(g,flows);
        Map<String,List<String>> params=new HashMap<>();params.put("code",Collections.singletonList("synthetic-code"));params.put("state",Collections.singletonList(f.state));
        NanoHTTPD.IHTTPSession session=(NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(Audit53DeviceProbe.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->{if(m.getName().equals("getParameters"))return params;if(m.getName().equals("getMethod"))return NanoHTTPD.Method.GET;if(m.getName().equals("getRemoteIpAddress"))return "127.0.0.1";return null;});
        NanoHTTPD.Response response=(NanoHTTPD.Response)call(g,"handleCodexHttpCallback",new Class[]{NanoHTTPD.IHTTPSession.class},session);
        String html=Audit52DeviceProbe.drain(response.getData());response.close();check(html.contains("&lt;script&gt;")&&!html.contains("<script>"),"Android actual OAuth callback escapes synthetic upstream HTML");
    }
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);if(!root.getPath().startsWith("/data/local/tmp/allin-audit53-"))throw new SecurityException("isolated scope required");root.mkdirs();
        try{UsageLedger.init(root);GatewayManagementCredential.init(root);PrivacyMasker.initialize(root);nativeBodies(root);dns();basics();callback(root);System.out.println("AUDIT53_ANDROID_PASS "+new JSONObject().put("checks",checks).put("api",android.os.Build.VERSION.SDK_INT).put("realAccounts",false));}
        finally{UsageLedger.setHooks(null);try{call(UsageLedger.class,"closeWriter",new Class[0]);}catch(Throwable ignored){}Audit52DeviceProbe.remove(root);}
    }
}
