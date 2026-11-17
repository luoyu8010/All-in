package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP listing and inference; all credentials and upstreams are synthetic. */
public final class QoderModelIdentityHttpTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static final String[][] MODELS={
        {"qmodel_latest","Qwen4-Max"},{"qmodel_latest_preview","Qwen4-Max-Preview"},
        {"qmodel_38max","Qwen3.8-Max"},{"qfmodel","Qwen3.8-Flash"},
        {"dmodel","DeepSeek-V4-Pro"},{"dfmodel","DeepSeek-Flash"},
        {"gmodel","GLM-5.3"},{"gfmodel","GLM-5.3-Flash"},
        {"kmodel_latest","Kimi-K3.1-Pro"},{"kmodel","Kimi-K3"},
        {"jmodel","J-Model"},{"mmodel","MiniMax-M3.1"},{"fast","Fast"}};
    static JSONObject provider(){
        JSONArray models=new JSONArray();JSONObject meta=new JSONObject();
        for(String[] row:MODELS){models.put(row[0]);meta.put(row[0],new JSONObject().put("key",row[0])
            .put("display_name",row[1]).put("enable",true).put("source","system").put("format","openai").put("is_vl",false));}
        JSONObject account=new JSONObject().put("id","synthetic-qoder").put("uid","synthetic-uid")
            .put("machineId","synthetic-machine").put("token","synthetic-token").put("deviceChat",true)
            .put("deviceToken","synthetic-device").put("expiresAt",System.currentTimeMillis()+86400000L)
            .put("models",new JSONArray(models.toString())).put("modelMeta",new JSONObject(meta.toString()));
        return new JSONObject().put("id","qoder_cn").put("name","Qoder CN").put("protocol","qoder")
            .put("enabled",true).put("models",models).put("modelMeta",meta).put("accounts",new JSONArray().put(account));
    }
    static final class Reply{int status;String text;JSONObject json(){return new JSONObject(text);}}
    static final class Fixture extends NanoHTTPD implements AutoCloseable{
        final GatewayServer backend;final HttpServer upstream;final File file;
        final AtomicInteger catalogs=new AtomicInteger(),chats=new AtomicInteger();
        volatile JSONObject sent;volatile String sentKey,sentUser;
        Fixture()throws Exception{
            super("127.0.0.1",0);
            JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","synthetic-gateway-key"))
                .put("privacy",new JSONObject().put("enabled",false))
                .put("routing",new JSONObject().put("groupsEnabled",true))
                .put("providers",new JSONArray().put(provider()).put(new JSONObject().put("id","other")
                    .put("protocol","openai").put("models",new JSONArray().put("gmodel"))
                    .put("modelMeta",new JSONObject().put("gmodel",new JSONObject().put("display_name","Other label")))
                    .put("keys",new JSONArray().put("synthetic-other"))));
            backend=RoutingSetupOfflineTest.server(cfg);
            file=Files.createTempDirectory("qoder-model-identity-").resolve("config.json").toFile();
            Field field=GatewayServer.class.getDeclaredField("configFile");field.setAccessible(true);field.set(backend,file);
            AtomicConfigFile.write(file,cfg.toString());UsageLedger.init(file.getParentFile());
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/",exchange->{
                try{
                    String wire;
                    if(exchange.getRequestURI().getPath().equals("/models")){
                        catalogs.incrementAndGet();JSONArray items=new JSONArray();JSONObject all=p().getJSONObject("modelMeta");
                        for(String[] row:MODELS)items.put(new JSONObject(all.getJSONObject(row[0]).toString()));
                        wire=new JSONObject().put("app",items).toString();
                    }else{
                        chats.incrementAndGet();sentKey=exchange.getRequestHeaders().getFirst("X-Model-Key");
                        sentUser=exchange.getRequestHeaders().getFirst("Cosy-User");
                        String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                        sent=new JSONObject(QoderAuth.decodeBody(body));
                        JSONObject chunk=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0)
                            .put("delta",new JSONObject().put("content","OK")).put("finish_reason",JSONObject.NULL)));
                        JSONObject end=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0)
                            .put("delta",new JSONObject()).put("finish_reason","stop")));
                        wire="data: "+new JSONObject().put("statusCodeValue",200).put("body",chunk.toString())+"\n\n"
                            +"data: "+new JSONObject().put("statusCodeValue",200).put("body",end.toString())+"\n\n"
                            +"data: [DONE]\n\n";
                        exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                    }
                    byte[] out=bytes(wire);exchange.sendResponseHeaders(200,out.length);
                    try(OutputStream stream=exchange.getResponseBody()){stream.write(out);}
                }catch(Exception failure){exchange.close();}
            });upstream.start();
            backend.setQoderConnectionsForTests(url->{
                String path;if(QoderAuth.CHAT_URL.equals(url))path="/chat";
                else if(QoderAuth.MODELS_URL.equals(url))path="/models";
                else throw new IOException("Unexpected upstream; real network forbidden");
                return (HttpURLConnection)new URL("http://127.0.0.1:"+upstream.getAddress().getPort()+path).openConnection(Proxy.NO_PROXY);
            });start(SOCKET_READ_TIMEOUT,true);
        }
        JSONObject config()throws Exception{return ResponsesGatewayOfflineTest.config(backend);}
        JSONObject p()throws Exception{return config().getJSONArray("providers").getJSONObject(0);}
        public Response serve(IHTTPSession request){return backend.serve(request);}
        Reply request(String path,JSONObject body,boolean management)throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+getListeningPort()+path).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);c.setReadTimeout(5000);c.setRequestProperty("Connection","close");
            if(management)c.setRequestProperty("X-All-In-Management",GatewayManagementCredential.get());
            else c.setRequestProperty("Authorization","Bearer synthetic-gateway-key");
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){out.write(bytes(body.toString()));}}
            try{Reply reply=new Reply();reply.status=c.getResponseCode();
                try(InputStream in=reply.status>=400?c.getErrorStream():c.getInputStream()){
                    reply.text=in==null?"":new String(in.readAllBytes(),StandardCharsets.UTF_8);}
                return reply;
            }finally{c.disconnect();}
        }
        Reply chat(String path,String id,boolean stream)throws Exception{
            JSONObject body=new JSONObject().put("model",id).put("stream",stream).put("max_tokens",64);
            if(path.equals("/v1/responses"))body.put("input","hello");
            else body.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
            return request(path,body,false);
        }
        public void close(){stop();upstream.stop(0);backend.setQoderConnectionsForTests(null);}
    }
    static Set<String> ids(JSONObject object,String key){
        Set<String> out=new LinkedHashSet<>();JSONArray rows=object.getJSONArray(key);
        for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i).getString("id"));return out;
    }

    static void pureNames(){
        JSONObject p=provider();JSONArray models=p.getJSONArray("models");JSONObject meta=p.getJSONObject("modelMeta");
        meta.getJSONObject("qmodel_latest").put("display_name","Qwen3.8-Max");
        QoderModelIdentity names=QoderModelIdentity.of(p);
        check(names.publicId("qmodel_latest").equals("Qwen3.8-Max (qmodel_latest)"),"duplicate label disambiguated");
        check(names.publicId("qmodel_38max").equals("Qwen3.8-Max (qmodel_38max)"),"both duplicate slots selectable");
        check(names.upstreamId("Qwen3.8-Max").equals("Qwen3.8-Max"),"ambiguous label never picks first slot");
        meta.getJSONObject("gmodel").put("display_name","dmodel");names=QoderModelIdentity.of(p);
        check(names.upstreamId("dmodel").equals("dmodel"),"raw ID wins over another label");
        check(names.upstreamId(names.publicId("gmodel")).equals("gmodel"),"raw-name collision round trip");
        meta.getJSONObject("gfmodel").put("display_name"," ").put("displayName"," GLM-Next ");
        check(QoderModelIdentity.of(p).publicId("gfmodel").equals("GLM-Next"),"field fallback and trimming");
        meta.getJSONObject("gfmodel").put("displayName","bad\r\nheader");
        check(QoderModelIdentity.of(p).publicId("gfmodel").equals("gfmodel"),"control label rejected");
        meta.getJSONObject("gfmodel").put("display_name",new JSONObject().put("token","synthetic"));
        check(QoderModelIdentity.of(p).publicId("gfmodel").equals("gfmodel"),"non-string label rejected");
        meta.remove("gmodel");
        check(QoderModelIdentity.of(p).publicId("gmodel").equals("GLM-5.3"),"account metadata fallback");
        Set<String> distinct=new HashSet<>();names=QoderModelIdentity.of(p);
        for(int i=0;i<models.length();i++){String key=models.getString(i),id=names.publicId(key);
            check(distinct.add(id),"unique ID "+key);check(names.upstreamId(id).equals(key),"all IDs reverse "+key);}
        JSONObject reversed=new JSONObject(p.toString());JSONArray reverse=new JSONArray();
        for(int i=models.length()-1;i>=0;i--)reverse.put(models.get(i));reversed.put("models",reverse);
        QoderModelIdentity reordered=QoderModelIdentity.of(reversed);
        for(int i=0;i<models.length();i++){String key=models.getString(i);
            check(names.publicId(key).equals(reordered.publicId(key)),"order independent "+key);}
    }
    public static void main(String[] args)throws Exception{
        pureNames();
        try(Fixture f=new Fixture()){
            String disk=new String(Files.readAllBytes(f.file.toPath()),StandardCharsets.UTF_8);
            Reply list=f.request("/v1/models",null,false);check(list.status==200,"model list status");
            Set<String> listed=ids(list.json(),"data");
            for(String[] row:MODELS){
                check(listed.contains("qoder_cn/"+row[1]),"agent sees official name "+row[1]);
                check(!listed.contains("qoder_cn/"+row[0]),"internal ID not advertised "+row[0]);
            }
            check(listed.contains("other/gmodel")&&!listed.contains("other/Other label"),"other provider unchanged");
            check(f.catalogs.get()==0&&f.chats.get()==0,"listing uses saved metadata only");
            check(disk.equals(new String(Files.readAllBytes(f.file.toPath()),StandardCharsets.UTF_8)),"list leaves config unchanged");
            for(String[] row:MODELS){
                Reply reply=f.chat("/v1/chat/completions","qoder_cn/"+row[1],false);
                check(reply.status==200,"listed name callable "+row[1]+" status "+reply.status+" "+reply.text);
                check(f.sent.getJSONObject("model_config").getString("key").equals(row[0]),"wire key "+row[0]);
                check(row[0].equals(f.sentKey),"signed model header "+row[0]);
                check(f.sent.getJSONObject("model_config").getString("display_name").equals(row[1]),"official config unchanged");
                check(reply.json().getString("model").equals("qoder_cn/"+row[1]),"reply keeps public name");
            }
            for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"}){
                for(boolean stream:new boolean[]{false,true}){
                    Reply reply=f.chat(path,"qoder_cn/GLM-5.3",stream);
                    check(reply.status==200&&reply.text.contains("OK"),"protocol "+path+" stream "+stream+" "+reply.text);
                    check(f.sent.getJSONObject("model_config").getString("key").equals("gmodel"),"protocol internal key");
                    check(reply.text.contains("qoder_cn/GLM-5.3"),"protocol reports client model");
                    if(stream)check(reply.text.contains(path.equals("/v1/messages")?"message_stop":path.equals("/v1/responses")?"response.completed":"[DONE]"),"stream ends "+path);
                }
            }
            Reply legacy=f.chat("/v1/chat/completions","qoder_cn/gmodel",false);
            check(legacy.status==200&&f.sentKey.equals("gmodel"),"old raw route callable");
            f.config().put("groups",new JSONArray().put(new JSONObject().put("id","raw").put("targets",new JSONArray().put("qoder_cn/gmodel")))
                .put(new JSONObject().put("id","named").put("targets",new JSONArray().put("qoder_cn/GLM-5.3"))));
            for(String group:new String[]{"raw","named"})check(f.chat("/v1/chat/completions","group/"+group,false).status==200,"group "+group);
            JSONObject exposed=new JSONObject().put("enabled",true).put("items",new JSONArray().put("qoder_cn/gmodel"));
            check(f.request("/api/exposed",exposed,true).status==200,"save old whitelist");
            Set<String> selected=ids(f.request("/v1/models",null,false).json(),"data");
            check(selected.size()==1&&selected.contains("qoder_cn/GLM-5.3"),"old whitelist filters renamed list");
            check(ids(f.request("/api/gateway-access",null,true).json(),"models").equals(selected),"access IDs agree with public list");
            exposed.put("items",new JSONArray().put("qoder_cn/GLM-5.3"));
            Reply saved=f.request("/api/exposed",exposed,true);
            check(saved.status==200&&saved.json().getInt("count")==1,"public whitelist accepted");
            check(f.config().getJSONObject("exposed").getJSONArray("items").getString(0).equals("qoder_cn/gmodel"),"whitelist persists raw identity");
            for(int i=0;i<3;i++)f.request("/v1/models",null,false);
            f.p().getJSONObject("modelMeta").getJSONObject("gmodel").put("display_name","GLM-Next");
            selected=ids(f.request("/v1/models",null,false).json(),"data");
            check(selected.size()==1&&selected.contains("qoder_cn/GLM-Next"),"official label change retains raw whitelist");
            f.config().getJSONObject("exposed").put("enabled",false);
            JSONObject metadata=f.p().getJSONObject("modelMeta");
            metadata.getJSONObject("qmodel_latest").put("display_name","Qwen3.8-Max");
            Set<String> duplicates=ids(f.request("/v1/models",null,false).json(),"data");
            for(String raw:new String[]{"qmodel_latest","qmodel_38max"}) {
                String publicId="qoder_cn/Qwen3.8-Max ("+raw+")";
                check(duplicates.contains(publicId),"both duplicate slots listed "+raw);
                check(f.chat("/v1/chat/completions",publicId,false).status==200 && raw.equals(f.sentKey),"duplicate label calls exact slot "+raw);
            }
            metadata.getJSONObject("gmodel").put("display_name","dmodel");
            check(f.chat("/v1/chat/completions","qoder_cn/dmodel (gmodel)",false).status==200 && "gmodel".equals(f.sentKey),"label/raw collision selects exact slot");
            check(f.chat("/v1/chat/completions","qoder_cn/dmodel",false).status==200 && "dmodel".equals(f.sentKey),"raw route never stolen by label");
            // Names resolve before the existing account-scoped entitlement checks, not around them.
            JSONObject original=f.p().getJSONArray("accounts").getJSONObject(0);
            JSONObject second=new JSONObject(original.toString()).put("id","synthetic-second").put("uid","synthetic-second-uid");
            original.put("models",new JSONArray().put("dmodel"));
            second.put("models",new JSONArray().put("gmodel"));
            f.p().put("accounts",new JSONArray().put(original).put(second));
            check(f.chat("/v1/chat/completions","qoder_cn/dmodel (gmodel)",false).status==200,"name routes to entitled second account");
            check("synthetic-second-uid".equals(f.sentUser),"wire authenticates the account that owns this slot");
            second.put("enabled",false);int sendsBefore=f.chats.get();
            check(RoutingSetupOfflineTest.candidates(f.backend,"qoder_cn/dmodel (gmodel)").isEmpty(),"disabled owner not replaced by another account");
            check(f.chats.get()==sendsBefore,"route inspection sends nothing");
            f.p().put("enabled",false);
            Set<String> disabledList=ids(f.request("/v1/models",null,false).json(),"data");
            check(disabledList.size()==1 && disabledList.contains("other/gmodel"),"disabled provider hidden, other provider retained");
            check(f.catalogs.get()==0,"no catalog or refresh requests in whole test");
        }
        System.out.println("QoderModelIdentityHttpTest PASS "+checks+" checks");
    }
}
