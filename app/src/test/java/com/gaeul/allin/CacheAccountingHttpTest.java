package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Actual ingress/response completion -> persisted usage, including streaming and both bridges. */
public final class CacheAccountingHttpTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static class ReplyConnection extends HttpURLConnection {
        final byte[] reply;final String mime;
        ReplyConnection(String text,String mime)throws IOException{super(new URL("http://127.0.0.1/synthetic"));reply=text.getBytes(StandardCharsets.UTF_8);this.mime=mime;}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public int getResponseCode(){return 200;}public InputStream getInputStream(){return new ByteArrayInputStream(reply);}
        public String getContentType(){return mime;}public String getHeaderField(String name){return "content-type".equalsIgnoreCase(name)?mime:null;}
    }
    static JSONObject usage(){return new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10)
        .put("prompt_tokens_details",new JSONObject().put("cached_tokens",0)).put("prompt_cache_hit_tokens",800);}
    static String standard(boolean stream){
        JSONObject content=new JSONObject().put("role","assistant").put("content","OK");
        JSONObject choice=new JSONObject().put("index",0).put(stream?"delta":"message",content).put("finish_reason","stop");
        JSONObject body=new JSONObject().put("id","synthetic-cache").put("object",stream?"chat.completion.chunk":"chat.completion")
            .put("model","synthetic").put("choices",new JSONArray().put(choice)).put("usage",usage());
        return stream?"data: "+body+"\n\ndata: [DONE]\n\n":body.toString();
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("cache-wire-");
        try{
            for(boolean google:new boolean[]{false,true})for(String path:new String[]{"/v1/chat/completions","/v1/responses","/v1/messages"})for(boolean stream:new boolean[]{false,true}){
                UsageLedger.init(dir.toFile());BlockingQueue<UsageLedger.Row> rows=new LinkedBlockingQueue<>();UsageLedger.setHooks(rows::add);
                GatewayServer gateway;
                if(google){AntigravityCompatibilityGatewayTest.Wire wire=new AntigravityCompatibilityGatewayTest.Wire(CacheAccountingRegressionTest.google(800,true));wire.status=200;
                    gateway=AntigravityCompatibilityGatewayTest.server(dir,wire);
                    Audit53RegressionTest.field(GatewayServer.class,"totalTokens").set(gateway,new AtomicLong());
                }else{
                    JSONObject provider=new JSONObject().put("id","deepseek").put("protocol","openai").put("enabled",true)
                        .put("keys",new JSONArray().put("synthetic-only")).put("models",new JSONArray().put("synthetic")).put("baseUrl","http://127.0.0.1/synthetic");
                    gateway=RoutingSetupOfflineTest.server(new JSONObject().put("server",new JSONObject().put("authKey","SYNTHETIC_INFERENCE"))
                        .put("setup",new JSONObject().put("done",true)).put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(provider)));
                    RoutingAuditOfflineTest.file(gateway,dir.resolve("config.json").toFile());
                    java.lang.reflect.Field factory=GatewayServer.class.getDeclaredField("vendorConnectionFactory");factory.setAccessible(true);
                    factory.set(gateway,(GatewayServer.VendorConnectionFactory)(url->new ReplyConnection(standard(stream),stream?"text/event-stream":"application/json")));
                }
                final GatewayServer backend=gateway;
                NanoHTTPD server=new NanoHTTPD("127.0.0.1",0){public Response serve(IHTTPSession session){return backend.serve(session);}};
                server.start(5000,true);
                try{
                    JSONObject request=new JSONObject().put("model",google?"antigravity/gemini-3.8-flash":"deepseek/synthetic").put("stream",stream).put("max_tokens",64);
                    if(path.equals("/v1/responses"))request.put("input","hello");
                    else request.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
                    HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getListeningPort()+path).openConnection(Proxy.NO_PROXY);
                    connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                    connection.setRequestProperty("Authorization","Bearer SYNTHETIC_INFERENCE");connection.setRequestProperty("Content-Type","application/json");
                    try(OutputStream out=connection.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
                    int status=connection.getResponseCode();String body;
                    try(InputStream in=status>=400?connection.getErrorStream():connection.getInputStream()){body=new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{connection.disconnect();}
                    check(status==200&&body.contains("OK"),"HTTP response "+google+" "+path+" stream="+stream+" status="+status+" "+body);
                    UsageLedger.Row row=rows.poll(3,TimeUnit.SECONDS);check(row!=null,"actual usage completion callback");
                    check(row.cacheRead==800&&"known".equals(row.cacheSource),"real ingress cache row "+google+" "+path+" stream="+stream);
                    check(row.cacheInputTokens==1000,"real ingress denominator");
                    UsageLedger.Totals totals=new UsageLedger.Totals();totals.add(row);check(totals.toJson().getDouble("hitRate")==80,"real ingress hit rate");
                    check(Files.exists(dir.resolve("usage")),"actual ledger directory");
                }finally{server.stop();gateway.stop();UsageLedger.setHooks(null);}
            }
            UsageLedger.init(dir.toFile());JSONObject totals=UsageLedger.summary(1,"provider").getJSONObject("totals");
            check(totals.getLong("cacheRead")>=800,"reloaded cache counters persisted");
            System.out.println("CacheAccountingHttpTest PASS checks="+checks);
        }finally{UsageLedger.setHooks(null);}
    }
}
