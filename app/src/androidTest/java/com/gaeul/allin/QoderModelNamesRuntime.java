package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Android ART, real local gateway and loopback Qoder bytes; no real accounts or vendor traffic. */
final class QoderModelNamesRuntime {
    int checks;
    void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int run(Context context)throws Exception{return new QoderModelNamesRuntime().verify(context);}
    static final String[][] MODELS={
        {"qmodel_latest","Qwen4-Max"},{"qmodel_latest_preview","Qwen4-Max-Preview"},
        {"qmodel_38max","Qwen3.8-Max"},{"qfmodel","Qwen3.8-Flash"},
        {"dmodel","DeepSeek-V4-Pro"},{"dfmodel","DeepSeek-Flash"},
        {"gmodel","GLM-5.3"},{"gfmodel","GLM-5.3-Flash"},
        {"kmodel_latest","Kimi-K3.1-Pro"},{"kmodel","Kimi-K3"},
        {"jmodel","J-Model"},{"mmodel","MiniMax-M3.1"},{"fast","Fast"}};
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static JSONObject config(){
        JSONArray models=new JSONArray();JSONObject meta=new JSONObject();
        for(String[] row:MODELS){models.put(row[0]);meta.put(row[0],new JSONObject().put("key",row[0])
            .put("display_name",row[1]).put("enable",true).put("source","system").put("format","openai"));}
        JSONObject account=new JSONObject().put("id","android-model-names-synthetic").put("uid","synthetic-uid")
            .put("machineId","synthetic-machine").put("token","synthetic-token").put("deviceToken","synthetic-device")
            .put("deviceChat",true).put("expiresAt",System.currentTimeMillis()+86400000L)
            .put("models",new JSONArray(models.toString())).put("modelMeta",new JSONObject(meta.toString()));
        JSONObject provider=new JSONObject().put("id","qoder_cn").put("name","Qoder CN").put("protocol","qoder")
            .put("enabled",true).put("models",models).put("modelMeta",meta).put("accounts",new JSONArray().put(account));
        return new JSONObject().put("server",new JSONObject().put("authKey","synthetic-model-names-gateway"))
            .put("setup",new JSONObject().put("done",true)).put("checkin",new JSONObject().put("autoEnabled",false))
            .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(provider));
    }
    static final class Upstream implements AutoCloseable {
        final ServerSocket listener;final Thread worker;final AtomicInteger sends=new AtomicInteger();
        volatile JSONObject body;volatile String key;volatile Throwable error;
        Upstream()throws Exception{
            listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(()->{
                while(!listener.isClosed())try(Socket socket=listener.accept()){
                    socket.setSoTimeout(5000);InputStream in=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
                    int state=0,b;
                    while(state<4&&(b=in.read())!=-1){headers.write(b);if(headers.size()>65536)throw new IOException("header cap");
                        state=state==0&&b==13?1:state==1&&b==10?2:state==2&&b==13?3:state==3&&b==10?4:0;}
                    int length=0;
                    for(String line:headers.toString("UTF-8").split("\r\n")){
                        String lower=line.toLowerCase(Locale.ROOT);
                        if(lower.startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                        if(lower.startsWith("x-model-key:"))key=line.substring(line.indexOf(':')+1).trim();
                    }
                    if(length<1||length>1048576)throw new IOException("fixture body size");
                    byte[] request=new byte[length];int at=0,n;while(at<length&&(n=in.read(request,at,length-at))>0)at+=n;
                    if(at!=length)throw new EOFException();body=new JSONObject(QoderAuth.decodeBody(new String(request,StandardCharsets.UTF_8)));sends.incrementAndGet();
                    JSONObject first=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0)
                        .put("delta",new JSONObject().put("content","OK")).put("finish_reason",JSONObject.NULL)));
                    JSONObject end=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0)
                        .put("delta",new JSONObject()).put("finish_reason","stop")));
                    byte[] response=bytes("data: "+new JSONObject().put("statusCodeValue",200).put("body",first.toString())+"\n\n"
                        +"data: "+new JSONObject().put("statusCodeValue",200).put("body",end.toString())+"\n\ndata: [DONE]\n\n");
                    OutputStream out=socket.getOutputStream();out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n"));out.write(response);out.flush();
                }catch(Throwable failure){if(!listener.isClosed())error=failure;}
            },"qoder-name-loopback");worker.start();
        }
        public void close()throws Exception{listener.close();worker.join(6000);if(worker.isAlive())throw new IOException("fixture did not stop");if(error!=null)throw new IOException(error);}
    }
    static final class Reply{int status;String text;JSONObject json(){return new JSONObject(text);}}
    Reply http(int port,String path,JSONObject body)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000);connection.setReadTimeout(8000);connection.setRequestProperty("Connection","close");
        connection.setRequestProperty("X-All-In-Management",GatewayManagementCredential.get());
        if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=connection.getOutputStream()){out.write(bytes(body.toString()));}}
        try{Reply reply=new Reply();reply.status=connection.getResponseCode();
            try(InputStream in=reply.status>=400?connection.getErrorStream():connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while(in!=null&&(n=in.read(b))!=-1)out.write(b,0,n);reply.text=out.toString("UTF-8");}
            return reply;
        }finally{connection.disconnect();}
    }
    static Set<String> ids(JSONObject root,String key){Set<String> result=new LinkedHashSet<>();JSONArray rows=root.getJSONArray(key);
        for(int i=0;i<rows.length();i++)result.add(rows.getJSONObject(i).getString("id"));return result;}
    int verify(Context target)throws Exception{
        File dir=new File(target.getFilesDir(),"qoder-model-names-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("fixture directory");
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}};
        JSONObject config=config();AtomicConfigFile.write(new File(dir,"config.json"),config.toString());
        GatewayServer gateway=new GatewayServer(isolated,0);AtomicInteger forbiddenReads=new AtomicInteger();
        try(Upstream upstream=new Upstream()){
            gateway.setQoderConnectionsForTests(url->{
                if(!QoderAuth.CHAT_URL.equals(url)){forbiddenReads.incrementAndGet();throw new IOException("catalog/refresh forbidden");}
                return (HttpURLConnection)new URL("http://127.0.0.1:"+upstream.listener.getLocalPort()+"/chat").openConnection(Proxy.NO_PROXY);
            });gateway.start(5000,true);int port=gateway.getListeningPort();
            Reply listing=http(port,"/v1/models",null);check(listing.status==200,"Android actual model listing HTTP 200");
            Set<String> publicIds=ids(listing.json(),"data");check(publicIds.size()==MODELS.length,"Android exact model count");
            for(String[] model:MODELS){check(publicIds.contains("qoder_cn/"+model[1]),"Android official name "+model[1]);
                check(!publicIds.contains("qoder_cn/"+model[0]),"Android internal ID no longer advertised "+model[0]);}
            check(upstream.sends.get()==0&&forbiddenReads.get()==0,"Android listing causes zero upstream calls");
            check(ids(http(port,"/api/gateway-access",null).json(),"models").equals(publicIds),"Android access copy IDs match public list");
            for(String[] model:MODELS){
                JSONObject input=new JSONObject().put("model","qoder_cn/"+model[1]).put("max_tokens",64)
                    .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
                Reply reply=http(port,"/v1/chat/completions",input);
                check(reply.status==200&&reply.text.contains("OK"),"Android returned name can be called "+model[1]);
                check(model[0].equals(upstream.key)&&model[0].equals(upstream.body.getJSONObject("model_config").optString("key")),"Android actual key and signed header "+model[0]);
                check(("qoder_cn/"+model[1]).equals(reply.json().optString("model")),"Android reply reports requested public ID");
            }
            for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})for(boolean stream:new boolean[]{false,true}){
                JSONObject input=new JSONObject().put("model","qoder_cn/GLM-5.3").put("stream",stream).put("max_tokens",64);
                if(path.equals("/v1/responses"))input.put("input","hello");
                else input.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
                Reply reply=http(port,path,input);check(reply.status==200&&reply.text.contains("OK"),"Android protocol "+path+" stream="+stream);
                check(reply.text.contains("qoder_cn/GLM-5.3")&&"gmodel".equals(upstream.key),"Android protocol model identity");
                if(stream)check(reply.text.contains(path.equals("/v1/messages")?"message_stop":path.equals("/v1/responses")?"response.completed":"[DONE]"),"Android complete SSE terminator");
            }
            JSONObject old=new JSONObject().put("model","qoder_cn/gmodel").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
            check(http(port,"/v1/chat/completions",old).status==200&&"gmodel".equals(upstream.key),"Android old raw ID still callable");
            Reply saved=http(port,"/api/exposed",new JSONObject().put("enabled",true).put("items",new JSONArray().put("qoder_cn/gmodel")));
            check(saved.status==200&&saved.json().optInt("count")==1,"Android old whitelist persists");
            Set<String> only=ids(http(port,"/v1/models",null).json(),"data");
            check(only.size()==1&&only.contains("qoder_cn/GLM-5.3"),"Android old whitelist selects public name");
            check(forbiddenReads.get()==0,"Android no catalog or refresh traffic");
            android.util.Log.i("ALLIN_QODER_NAMES","PASS checks="+checks+" officialModels="+MODELS.length+" internalIds=0 loopbackChats="+upstream.sends.get());
            return checks;
        }finally{gateway.stop();delete(dir);}
    }
    static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
}
