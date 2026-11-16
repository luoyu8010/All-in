package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual forwarding paths, not only keyword/helper assertions. All HTTP is loopback or synthetic. */
public final class Audit53RoutingRegressionTest {
    static int checks;static final List<String> failures=new ArrayList<>();
    static void check(boolean ok,String label){checks++;if(!ok){failures.add(label);System.out.println("FAIL "+label);}}
    interface Case { void run()throws Exception; }
    static void run(String name,Case c){try{c.run();}catch(Throwable e){failures.add(name+" "+e.getClass().getSimpleName());System.out.println("FAIL "+name+" "+e.getClass().getSimpleName()+" "+e.getMessage());}}
    static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    static Object call(Object o,String n,Class<?>[] t,Object...args)throws Exception{return Audit53RegressionTest.call(o,n,t,args);}
    static final List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<>());
    static void reset(){rows.clear();UsageLedger.setHooks(rows::add);}
    static void quotaFallback()throws Exception{
        for(String message:new String[]{"超出配额","账户超出上限，余额不足","上下文超长","messages missing"}){
            boolean retry=message.contains("配额")||message.contains("余额");reset();
            try(VendorPolicyGatewayHttpTest.Upstream up=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(n==1?400:200,"application/json",n==1?new JSONObject().put("error",new JSONObject().put("message",message)).toString():VendorPolicyGatewayHttpTest.CHAT))){
                JSONObject first=VendorPolicyGatewayHttpTest.provider("first","openai","https://synthetic.invalid/first","m");
                first.put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("token",VendorPolicyGatewayHttpTest.KEY).put("type","api")));
                JSONObject second=VendorPolicyGatewayHttpTest.provider("second","openai","https://synthetic.invalid/second","m");
                second.put("accounts",new JSONArray().put(new JSONObject().put("id","b").put("token",VendorPolicyGatewayHttpTest.KEY).put("type","api")));
                JSONObject cfg=VendorPolicyGatewayHttpTest.config(first);cfg.getJSONArray("providers").put(second);
                cfg.put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put("first/m").put("second/m"))));
                try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,up,false)){
                    VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body("group/g",false));
                    check(r.status==(retry?200:400),"M4 real route status "+message);
                    check(up.requests.size()==(retry?2:1),"M4 measured sends "+message);
                }
            }
        }
    }
    static void raccoonReadmission()throws Exception{
        reset();
        try(VendorPolicyGatewayHttpTest.Upstream up=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(401,"application/json","{\"error\":{\"message\":\"expired\"}}"))){
            JSONObject cfg=VendorPolicyGatewayHttpTest.config(ProviderParityWiringHttpTest.raccoonProvider("glm-upstream",ProviderParityWiringHttpTest.raccoonAccount("old-access","r1")));
            try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,up,false)){
                AtomicInteger opens=new AtomicInteger(),refreshes=new AtomicInteger();
                g.gateway.setVendorConnectionsForTests(url->{
                    if(opens.incrementAndGet()==2)try{synchronized(g.gateway){JSONObject fresh=new JSONObject(ResponsesGatewayOfflineTest.config(g.gateway).toString());fresh.getJSONArray("providers").getJSONObject(0).put("enabled",false);field(GatewayServer.class,"configJson").set(g.gateway,fresh);}}catch(Exception e){throw new IOException(e);}
                    return NetworkPolicy.openHttp("http://127.0.0.1:"+up.server.getLocalPort()+new URL(url).getFile());
                });
                g.gateway.setRaccoonRefreshTransportForTests((path,body)->{refreshes.incrementAndGet();return new RaccoonRefreshService.Response(200,new JSONObject().put("code",0).put("data",new JSONObject().put("access_token","new-access").put("refresh_token","r2").put("expires_in",3600)));});
                Object target=RoutingSetupOfflineTest.candidates(g.gateway,"raccoon/glm-upstream").get(0);
                Object attempt=call(g.gateway,"forwardAttempt",new Class[]{target.getClass(),JSONObject.class,boolean.class,String.class,PrivacyMasker.Session.class,ContextGuard.Config.class,ContextGuard.Result.class},target,ProviderParityWiringHttpTest.chat("raccoon/glm-upstream"),false,"raccoon/glm-upstream",new PrivacyMasker.Session(),ContextGuard.Config.fromJson(null),null);
                check(refreshes.get()==1&&opens.get()==2&&up.requests.size()==1,"M3 one actual inference send, one renewal, second admission rejected");
                check(field(attempt.getClass(),"sent").getBoolean(attempt),"M3 send evidence retained");
                check(!field(attempt.getClass(),"localRejection").getBoolean(attempt),"M3 resent admission is not classified wholly local");
                check(rows.size()==1&&rows.get(0).outboundAt>0,"M3 ledger retains actual outbound witness");
                if(!rows.isEmpty())check(!"not_sent".equals(RequestDiagnostics.json(rows.get(0)).optString("executionState")),"M3 ledger does not invent not-sent");
            }
        }
    }
    static void copilotOldConnection()throws Exception{
        reset();
        try(ResponsesGatewayOfflineTest.Mock upstream=new ResponsesGatewayOfflineTest.Mock(Audit53NativeLifecycleTest.sse(),true)) {
            GatewayServer g=ResponsesGatewayOfflineTest.gateway(upstream.server.getLocalPort());
            ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(0).put("id","github-copilot");
            Audit53NativeLifecycleTest.Conn first=new Audit53NativeLifecycleTest.Conn("{\"error\":{\"code\":\"unsupported_api_for_model\",\"message\":\"unsupported_api_for_model\"}}",false,400);
            AtomicInteger opened=new AtomicInteger();
            g.setVendorConnectionsForTests(url->{opened.incrementAndGet();return first;});
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(VendorPolicyGatewayHttpTest.body("github-copilot/m",true).toString(),"secret","/v1/chat/completions"));
            try {
                check(opened.get()==1 && upstream.received!=null && upstream.requestLine.contains("/responses "),"L14 Copilot actually adopted a new Responses connection");
                check(first.disconnects.get()>0,"L14 old 4xx connection closed before streaming handoff");
                check(ResponsesGatewayOfflineTest.read(r.getData()).contains("[DONE]"),"L14 adopted stream preserves completed response");
            } finally {r.close();}
        }
    }
    static void workbuddyEstimate()throws Exception{
        for(String provider:new String[]{"workbuddy","workbuddy-ai"}){reset();String sse=GatewayStreamReadOfflineTest.content("estimated text")+GatewayStreamReadOfflineTest.done();
            try(VendorPolicyGatewayHttpTest.Upstream up=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",sse))){
                JSONObject cfg=VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider(provider,"openai",provider.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2","m"));
                try(VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,up,false)){
                    VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",VendorPolicyGatewayHttpTest.body(provider+"/m",false));
                    JSONObject usage=new JSONObject(r.body).getJSONObject("usage");check(r.status==200&&usage.optBoolean("estimated"),"L17 client explicitly receives estimated counters "+provider);
                    UsageLedger.Row row=rows.isEmpty()?null:rows.get(rows.size()-1);
                    check(row!=null&&"estimated".equals(row.usageSource)&&row.in==usage.getInt("prompt_tokens")&&row.out==usage.getInt("completion_tokens"),"L17 ledger agrees without pretending actual usage "+provider);
                }
            }
        }
    }
    static void cancellation()throws Exception{
        for(boolean sourceError:new boolean[]{false,true}){reset();UsageLedger.Row row=new UsageLedger.Row();row.requestId="synthetic-stream";
            InputStream source=sourceError?new InputStream(){public int read()throws IOException{throw new IOException("synthetic source error");}}:new ByteArrayInputStream((GatewayStreamReadOfflineTest.content("partial")+GatewayStreamReadOfflineTest.done()).getBytes(StandardCharsets.UTF_8));
            InputStream stream=GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),source,new GatewayStreamReadOfflineTest.Connection(),null,row);
            if(sourceError)ResponsesGatewayOfflineTest.read(stream);else stream.read();stream.close();
            check(rows.size()==1&&row.status==(sourceError?502:499),"L22 distinguishes "+(sourceError?"upstream error":"client close"));
        }
    }
    static void helpers(Path root)throws Exception{
        byte[] expected="汉字 and UTF-8; short chunks".getBytes(StandardCharsets.UTF_8);AtomicBoolean closed=new AtomicBoolean();
        byte[] actual=GatewayServer.readFileBytes(new ByteArrayInputStream(expected){public int available(){return 0;}public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(2,n));}public void close(){closed.set(true);}});
        check(Arrays.equals(expected,actual)&&closed.get(),"M10 available=0/short reads preserve all bytes and close");
        closed.set(false);boolean threw=false;
        try{GatewayServer.readFileBytes(new InputStream(){public int read()throws IOException{throw new IOException("synthetic read fault");}public void close(){closed.set(true);}});}catch(IOException e){threw=true;}
        check(threw&&closed.get(),"L13 failed owned-stream read always closes");
        String secret="SYNTHETIC_SECRET_SENTINEL";Exception error=new IOException(secret,new IllegalStateException(secret));error.addSuppressed(new IOException(secret));
        String log=GatewayServer.safeLogMessage("fixture",error);check(log.equals("fixture: IOException")&&!log.contains(secret),"L4 class-only diagnostic excludes message/cause/suppressed data");
        check("bigmodel".equals(GatewayServer.zcodeSiteForBase("https://open.bigmodel.cn"))&&"zai".equals(GatewayServer.zcodeSiteForBase("https://api.z.ai"))&&"zai".equals(GatewayServer.zcodeSiteForBase(null)),"L20 correct Boolean site choice and null defense");
        for(String bad:new String[]{"10.0.0.1","127.0.0.1","::1","cloudcode-pa.googleapis.com.evil.invalid","evilgoogleapis.com","cloudcode-pa.googleapis.com@evil.invalid","https://cloudcode-pa.googleapis.com"," cloudcode-pa.googleapis.com"})check(!GatewayServer.allowedAntigravityDiagnosticHost(bad),"M6 exact host allowlist rejects "+bad);
        check(GatewayServer.allowedAntigravityDiagnosticHost("CLOUDCODE-PA.GOOGLEAPIS.COM"),"M6 case-insensitive genuine business host allowed");
        GatewayServer g=Audit53RegressionTest.gateway();Audit53RegressionTest.flows(g);
        check(call(g,"pendingCodexFlow",new Class[]{String.class,String.class},"c1",null)==Audit53RegressionTest.codex(g).get("c1"),"M5 explicit raw-code flow still works");
        check(call(g,"pendingCodexFlow",new Class[]{String.class,String.class},"","cs2")==Audit53RegressionTest.codex(g).get("c2"),"M5 exact state-only flow still works");
        check(call(g,"pendingCodexFlow",new Class[]{String.class,String.class},"c1","cs1")!=null,"M5 matching id and state still work");
        check(call(g,"pendingCodexFlow",new Class[]{String.class,String.class},"missing","cs1")==null,"M5 explicit wrong id never silently falls back to another state");
        @SuppressWarnings("unchecked") Map<String,String> params=(Map<String,String>)call(GatewayServer.class,"manualOAuthInput",new Class[]{String.class},"http://localhost/callback?code=a%2Bb%3D%3D&state=cs1");
        check("a+b==".equals(params.get("code"))&&"cs1".equals(params.get("state")),"M5 URL encoding and equals in code preserved");
        boolean duplicate=false;try{call(GatewayServer.class,"manualOAuthInput",new Class[]{String.class},"?code=a&state=1&state=2");}catch(IllegalArgumentException e){duplicate=true;}check(duplicate,"M5 duplicate callback selectors rejected");
        Path file=root.resolve("watchdog.json");Files.write(file,"{\"unfreezeCount\":1}".getBytes(StandardCharsets.UTF_8));field(GatewayServer.class,"watchdogFile").set(g,file.toFile());field(GatewayServer.class,"watchdogState").set(g,new JSONObject().put("unfreezeCount",2));
        call(g,"saveWatchdogState",new Class[0]);check(new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getInt("unfreezeCount")==2,"L27 complete watchdog replacement readable");
        try(java.util.stream.Stream<Path> list=Files.list(root)){check(list.noneMatch(p->p.getFileName().toString().startsWith("config-")&&p.toString().endsWith(".tmp")),"L27 no leftover temporary replacement file");}
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("audit53-routing-");UsageLedger.init(root.toFile());
        try{run("quota fallback",Audit53RoutingRegressionTest::quotaFallback);run("raccoon readmission",Audit53RoutingRegressionTest::raccoonReadmission);run("copilot adoption",Audit53RoutingRegressionTest::copilotOldConnection);run("WorkBuddy estimates",Audit53RoutingRegressionTest::workbuddyEstimate);run("cancellation",Audit53RoutingRegressionTest::cancellation);
            if(args.length==0)run("helpers",()->helpers(root));
            System.out.println("Audit53RoutingRegressionTest checks="+checks+" failures="+failures.size());if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        }finally{UsageLedger.setHooks(null);GatewayStreamReadOfflineTest.closeLedger();NetworkPolicy.configure(null);try(java.util.stream.Stream<Path> s=Files.walk(root)){for(Path p:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
}
