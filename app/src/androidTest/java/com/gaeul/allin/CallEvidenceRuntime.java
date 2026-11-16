package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual Android gateway, HTTP, account reference and local cooldown; all Google bytes are synthetic. */
final class CallEvidenceRuntime {
    int checks;void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int run(Context context)throws Exception{return new CallEvidenceRuntime().verify(context);}
    static final class Reply {int status;String id;JSONObject body;}
    Reply post(int port,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-All-In-Management",GatewayManagementCredential.get());
        try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        try{Reply r=new Reply();r.status=c.getResponseCode();r.id=c.getHeaderField("X-AllIn-Request-Id");try(InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)out.write(b,0,n);r.body=new JSONObject(out.toString("UTF-8"));}return r;
        }finally{c.disconnect();}
    }
    int verify(Context context)throws Exception{
        File dir=new File(context.getFilesDir(),"call57-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("fixture directory");
        Context isolated=new ContextWrapper(context){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}};
        String model="gemini-3.8-flash";
        JSONObject account=new JSONObject().put("id","ANDROID_ACCOUNT_PRIVATE").put("enabled",true).put("access","ANDROID_ACCESS_PRIVATE")
                .put("token","ANDROID_ACCESS_PRIVATE").put("refresh","ANDROID_REFRESH_PRIVATE").put("project","android-synthetic-project").put("expires",System.currentTimeMillis()+3600000);
        JSONObject metadata=new JSONObject().put("defaultId",model+"-low").put("variants",new JSONObject().put("low",model+"-low").put("high",model+"-high"));
        JSONObject config=new JSONObject().put("setup",new JSONObject().put("done",true)).put("privacy",new JSONObject().put("enabled",false))
                .put("server",new JSONObject().put("authKey","ANDROID_SYNTHETIC_GATEWAY"))
                .put("providers",new JSONArray().put(new JSONObject().put("id","antigravity").put("protocol","antigravity").put("models",new JSONArray().put(model))
                        .put("accounts",new JSONArray().put(account)).put("modelMeta",new JSONObject().put(model,metadata))));
        try(OutputStream out=new FileOutputStream(new File(dir,"config.json"))){out.write(config.toString().getBytes(StandardCharsets.UTF_8));}
        final int[] status={200},sends={0};final String[] reply={"data: "+new JSONObject().put("response",new JSONObject().put("candidates",new JSONArray().put(new JSONObject()
                .put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","OK")))).put("finishReason","STOP"))))+"\n\n"};
        GatewayServer gateway=new GatewayServer(isolated,0);
        gateway.setAntigravityTransportForTests((url,host,port,connect,read)->{
            sends[0]++;byte[] body=reply[0].getBytes(StandardCharsets.UTF_8);
            byte[] head=("HTTP/1.1 "+status[0]+" Synthetic\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            return new AntigravityAuth.Link(){public InputStream in(){return new SequenceInputStream(new ByteArrayInputStream(head),new ByteArrayInputStream(body));}public OutputStream out(){return new ByteArrayOutputStream();}public void close(){}};
        });
        try{
            gateway.start(5000,true);int port=gateway.getListeningPort();
            Reply ok=post(port,"/api/test-model",new JSONObject().put("model","antigravity/"+model));
            check(ok.status==200&&ok.body.optBoolean("ok"),"Android ordinary test succeeds");check(ok.id!=null&&ok.id.equals(ok.body.optString("requestId")),"Android probe response/ledger correlation ID");
            status[0]=429;reply[0]=new JSONObject().put("error",new JSONObject().put("code",429).put("message","Resource has been exhausted (e.g. check quota).").put("status","RESOURCE_EXHAUSTED")).toString();
            JSONObject input=new JSONObject().put("model","antigravity/"+model).put("reasoning_effort","high").put("messages",new JSONArray()
                    .put(new JSONObject().put("role","system").put("content","ANDROID_SECRET_PROMPT")).put(new JSONObject().put("role","user").put("content","ANDROID_SECRET_USER")))
                    .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","ANDROID_SECRET_TOOL").put("parameters",new JSONObject().put("type","object")))));
            Reply failed=post(port,"/v1/chat/completions",input);check(failed.status==429&&failed.id!=null&&!failed.id.equals(ok.id),"Android Agent-shaped 429 distinct from success");
            Reply local=post(port,"/api/test-model",new JSONObject().put("model","antigravity/"+model));
            check(sends[0]==2,"Android local cooldown does not add a third send");check("本地限流冷却".equals(local.body.optString("failureLabel")),"Android cooldown advice separated");
            JSONArray rows=UsageLedger.recentRequests(30);JSONObject success=null,upstream=null,blocked=null;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optInt("attempt")<=0)continue;String id=row.optString("requestId");if(id.equals(ok.id))success=row.optJSONObject("callEvidence");if(id.equals(failed.id))upstream=row.optJSONObject("callEvidence");if(id.equals(local.id))blocked=row.optJSONObject("callEvidence");}
            check(success!=null&&upstream!=null&&blocked!=null,"Android ledger contains all three diagnostic snapshots");
            check((model+"-low").equals(success.optString("sentModel"))&&(model+"-high").equals(upstream.optString("sentModel")),"Android actual variants distinguish low from high");
            check(success.optString("accountRef").equals(upstream.optString("accountRef"))&&upstream.optString("accountRef").equals(blocked.optString("accountRef")),"Android account comparison without raw identity");
            check("/v1/chat/completions".equals(upstream.optString("path"))&&"upstream".equals(upstream.optString("source")),"Android ingress and upstream rejection");
            check(upstream.optBoolean("systemPresent")&&upstream.optInt("toolCount")==1&&"high".equals(upstream.optString("effort")),"Android request shape counters");
            check(upstream.isNull("upstreamRetryAfterSeconds")&&blocked.optLong("localCooldownRemainingMs")>0,"Android wait units and absent header");
            check("gateway_cooldown".equals(blocked.optString("source"))&&blocked.isNull("sentModel")&&blocked.optInt("physicalSends")==0,"Android blocked request does not claim a variant send");
            check(failed.id.equals(blocked.optString("cooldownOriginRequestId")),"Android cooldown links to initial refusal");
            String facts=success.toString()+upstream+blocked;check(!facts.contains("ANDROID_SECRET_")&&!facts.contains("ANDROID_ACCESS_PRIVATE")&&!facts.contains("ANDROID_ACCOUNT_PRIVATE"),"Android snapshots exclude content and raw identity");
            return checks;
        }finally{gateway.stop();delete(dir);}
    }
    static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
}
