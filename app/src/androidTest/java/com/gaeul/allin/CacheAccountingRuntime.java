package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Android ART counters plus real gateway HTTP -> durable ledger, with synthetic Google bytes. */
final class CacheAccountingRuntime {
    int checks;
    void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static int run(Context target)throws Exception{return new CacheAccountingRuntime().verify(target);}
    UsageLedger.Row absorb(JSONObject u)throws Exception{
        UsageLedger.Row row=new UsageLedger.Row();row.requestId="android-synthetic-cache";row.status=200;
        Method method=GatewayServer.class.getDeclaredMethod("absorbUsage",UsageLedger.Row.class,JSONObject.class);method.setAccessible(true);method.invoke(null,row,u);return row;
    }
    JSONObject totals(UsageLedger.Row row){UsageLedger.Totals totals=new UsageLedger.Totals();totals.add(row);return totals.toJson();}
    int verify(Context target)throws Exception{
        for(String provider:new String[]{"zcode","workbuddy","workbuddy-ai","trae-cn","catpaw","deepseek_web","raccoon","loomy","deepseek","siliconflow","openai","anthropic","openrouter","tencent","qoder_cn","cline","codex","antigravity","github-copilot","minimax-code"}){
            UsageLedger.Row row=absorb(new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10).put("prompt_tokens_details",new JSONObject().put("cached_tokens",0)).put("prompt_cache_hit_tokens",800));
            check(row.cacheRead==800&&totals(row).getDouble("hitRate")==80,"Android shared aliases "+provider);
        }
        UsageLedger.Row nativeRow=absorb(new JSONObject().put("input_tokens",200).put("output_tokens",10).put("cache_read_input_tokens",600).put("cache_creation_input_tokens",200));
        check(totals(nativeRow).getDouble("hitRate")==60,"Android cache writes in native denominator");
        UsageLedger.Row unknown=absorb(new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10));check(totals(unknown).isNull("hitRate"),"Android unknown never a zero");
        UsageLedger.Row zero=absorb(new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10).put("cached_tokens",0));check(totals(zero).getDouble("hitRate")==0,"Android explicit zero preserved");
        File dir=new File(target.getFilesDir(),"cache-accounting-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("fixture directory");
        Context isolated=new ContextWrapper(target){public File getFilesDir(){return dir;}public Context getApplicationContext(){return this;}};
        JSONObject account=new JSONObject().put("id","synthetic-cache-account").put("token","synthetic-cache-access").put("access","synthetic-cache-access")
            .put("refresh","synthetic-cache-refresh").put("project","synthetic-project").put("expires",System.currentTimeMillis()+3600000);
        JSONObject provider=new JSONObject().put("id","antigravity").put("protocol","antigravity").put("models",new JSONArray().put("gemini-3.8-flash"))
            .put("accounts",new JSONArray().put(account));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("checkin",new JSONObject().put("autoEnabled",false))
            .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(provider));
        AtomicConfigFile.write(new File(dir,"config.json"),cfg.toString());GatewayServer gateway=new GatewayServer(isolated,0);
        gateway.setAntigravityTransportForTests((url,host,port,connect,read)->{
            JSONObject usage=new JSONObject().put("promptTokenCount",1000).put("cachedContentTokenCount",800).put("candidatesTokenCount",10);
            JSONObject google=new JSONObject().put("response",new JSONObject().put("usageMetadata",usage).put("candidates",new JSONArray().put(new JSONObject().put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","OK")))).put("finishReason","STOP"))));
            byte[] body=("data: "+google+"\n\n").getBytes(StandardCharsets.UTF_8);
            byte[] header=("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            return new AntigravityAuth.Link(){public InputStream in(){return new SequenceInputStream(new ByteArrayInputStream(header),new ByteArrayInputStream(body));}public OutputStream out(){return new ByteArrayOutputStream();}public void close(){}};
        });
        try{
            gateway.start(5000,true);int port=gateway.getListeningPort();
            for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})for(boolean stream:new boolean[]{false,true}){
                BlockingQueue<UsageLedger.Row> rows=new LinkedBlockingQueue<>();UsageLedger.setHooks(rows::add);
                JSONObject request=new JSONObject().put("model","antigravity/gemini-3.8-flash").put("stream",stream).put("max_tokens",64);
                if(path.equals("/v1/responses"))request.put("input","hello");else request.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
                HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(3000);connection.setReadTimeout(8000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("X-All-In-Management",GatewayManagementCredential.get());
                try(OutputStream out=connection.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
                int status=connection.getResponseCode();String body;
                try(InputStream in=status>=400?connection.getErrorStream():connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[2048];int n;while(in!=null&&(n=in.read(b))!=-1)out.write(b,0,n);body=out.toString("UTF-8");}finally{connection.disconnect();}
                check(status==200&&body.contains("OK"),"Android cache real HTTP "+path+" stream="+stream+" status="+status);
                UsageLedger.Row row=rows.poll(3,TimeUnit.SECONDS);check(row!=null&&row.cacheRead==800,"Android real request records cache");
                check(row.cacheInputTokens==1000&&totals(row).getDouble("hitRate")==80,"Android real request rate=80");
            }
            UsageLedger.setHooks(null);gateway.stop();UsageLedger.init(dir);
            JSONObject summary=UsageLedger.summary(1,"provider").getJSONObject("totals");
            check(summary.getLong("cacheRead")==4800&&summary.getLong("cacheKnownInputTokens")==6000,"Android all six requests survive ledger restart");
            check(summary.getDouble("hitRate")==80,"Android persisted summary rate=80");
            return checks;
        }finally{UsageLedger.setHooks(null);gateway.stop();delete(dir);}
    }
    static int webView(android.app.Instrumentation instrumentation)throws Exception {
        String html;
        try(InputStream in=instrumentation.getTargetContext().getAssets().open("dashboard.html");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);html=out.toString("UTF-8");
        }
        int start=html.indexOf("    function cacheHitText("),end=html.indexOf("    let usageRevision",start);
        if(start<0||end<=start)throw new AssertionError("Android packaged dashboard cache formatter missing");
        final String function=html.substring(start,end);
        final CountDownLatch done=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> answer=new java.util.concurrent.atomic.AtomicReference<>();
        final android.webkit.WebView[] view={null};
        instrumentation.runOnMainSync(()->{
            view[0]=new android.webkit.WebView(instrumentation.getTargetContext());view[0].getSettings().setJavaScriptEnabled(true);
            view[0].setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(android.webkit.WebView web,String url){
                web.evaluateJavascript("(function(){"+function+"return JSON.stringify([cacheHitText({hitRate:null}),cacheHitText({hitRate:0,hitRateExact:0}),cacheHitText({hitRate:0,hitRateExact:0.01}),cacheHitText({hitRate:80,hitRateExact:80})]);})()",value->{answer.set(value);done.countDown();});
            }});view[0].loadDataWithBaseURL("https://cache-fixture.invalid/","<html><body>Cache formatter fixture</body></html>","text/html","UTF-8",null);
        });
        try {
            if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("Android WebView cache display timed out");
            Object decoded=new JSONTokener(answer.get()).nextValue();JSONArray actual=new JSONArray((String)decoded);
            String[] expected={"—","0%","<0.1%","80%"};
            for(int i=0;i<expected.length;i++)if(!expected[i].equals(actual.optString(i)))throw new AssertionError("Android WebView cache display "+i);
            return 5;
        } finally {instrumentation.runOnMainSync(()->{if(view[0]!=null)view[0].destroy();});}
    }
    static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
}
