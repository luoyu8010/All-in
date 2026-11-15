package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Additional advisory reproductions: real local sockets, original read paths, no vendor access. */
public final class AdvisoryBoundaryOfflineTest {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static Object invoke(Method m,Object owner,Object...args)throws Exception{return ConfigConsistencyOfflineTest.invoke(m,owner,args);}
    static void siblingRefresh(String type)throws Exception {
        try(ConfigConsistencyOfflineTest.Fixture f=new ConfigConsistencyOfflineTest.Fixture()) {
            JSONObject config=ConfigConsistencyOfflineTest.copy(ConfigConsistencyOfflineTest.root(f.server));
            config.getJSONArray("providers").put(new JSONObject().put("id",type).put("enabled",true).put("accounts",new JSONArray().put(ConfigConsistencyOfflineTest.account("sibling").put("generation","1"))));
            Method persist=GatewayServer.class.getDeclaredMethod("persistConfigStrict",JSONObject.class);persist.setAccessible(true);invoke(persist,f.server,config);
            if("cline".equals(type)) {
                f.server.setClineRefreshForTests(token->{ConfigConsistencyOfflineTest.call(f,"/api/guard","POST",new JSONObject().put("enabled",false));ClineAuth.SignIn n=new ClineAuth.SignIn();n.access="synthetic-cline-next";n.refresh="synthetic-cline-rotated";n.expires=System.currentTimeMillis()+3600000;return n;});
                Method m=GatewayServer.class.getDeclaredMethod("refreshClineAccount",JSONObject.class);m.setAccessible(true);invoke(m,f.server,ConfigConsistencyOfflineTest.account(config,type,"sibling"));
                check("synthetic-cline-rotated".equals(ConfigConsistencyOfflineTest.account(f.disk(),type,"sibling").optString("refresh")),"Cline settings do not discard a rotation");
            } else if("codex".equals(type)) {
                CodexAuth.SignInInfo info=CodexPersistenceOfflineTest.oldInfo();Method bind=GatewayServer.class.getDeclaredMethod("bindCodexPersistence",JSONObject.class,CodexAuth.SignInInfo.class);bind.setAccessible(true);invoke(bind,f.server,ConfigConsistencyOfflineTest.account(config,type,"sibling"),info);
                ConfigConsistencyOfflineTest.call(f,"/api/guard","POST",new JSONObject().put("enabled",false));info.refreshCommit.commit(info,CodexPersistenceOfflineTest.candidate());
                check("new-refresh".equals(ConfigConsistencyOfflineTest.account(f.disk(),type,"sibling").optString("refresh")),"Codex settings do not discard a rotation");
            } else {
                Method control=GatewayServer.class.getDeclaredMethod("traeControl");control.setAccessible(true);TraeControl c=(TraeControl)invoke(control,f.server);
                Field sf=TraeControl.class.getDeclaredField("store");sf.setAccessible(true);TraeAccounts.Store store=(TraeAccounts.Store)sf.get(c);JSONObject expected=store.current("sibling");
                ConfigConsistencyOfflineTest.call(f,"/api/guard","POST",new JSONObject().put("enabled",false));store.replace("sibling",expected,new JSONObject(expected.toString()).put("token","synthetic-trae-next"));
                check("synthetic-trae-next".equals(ConfigConsistencyOfflineTest.account(f.disk(),type,"sibling").optString("token")),"Trae settings do not invalidate its account store");
            }
        }
    }
    static void importedKey(String provider)throws Exception {
        JSONObject p=new JSONObject().put("id",provider).put("enabled",true).put("protocol","openai").put("accounts",new JSONArray()).put("keys",new JSONArray()).put("models",new JSONArray().put("m"));
        JSONObject config=new JSONObject().put("providers",new JSONArray().put(p));String secret="ck_synthetic-import-only";
        JSONObject input=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id",provider).put("keys",new JSONArray().put(new JSONObject().put("key",secret).put("enabled",true)))));
        AccountTransferCodec.Document d=AccountTransferCodec.parse(input.toString().getBytes(StandardCharsets.UTF_8),provider);check(d.entries.size()==1&&d.entries.get(0).valid(),"real codec accepts standalone key");
        AccountTransferPlan.Result merged=AccountTransferPlan.merge(config,d,Collections.singleton(0),false,System.currentTimeMillis());check(merged.changed,"real import merge saves key");
        GatewayServer g=RoutingSetupOfflineTest.server(merged.config);java.util.List<?> routes=RoutingSetupOfflineTest.candidates(g,provider+"/m");check(routes.size()==1,"imported object key reaches actual gateway route: "+provider);
        Field keyField=routes.get(0).getClass().getDeclaredField("apiKey");keyField.setAccessible(true);
        check(secret.equals(keyField.get(routes.get(0))),"actual gateway route uses the key value, never serialized object JSON: "+provider);
        if("zcode".equals(provider)) {
            JSONObject withGift=new JSONObject(merged.config.toString());withGift.getJSONArray("providers").getJSONObject(0).put("accounts",new JSONArray().put(new JSONObject().put("id","gift-account").put("enabled",true).put("jwt","synthetic-gift-session")));
            java.util.List<?> paid=RoutingSetupOfflineTest.candidates(RoutingSetupOfflineTest.server(withGift),"zcode/m");
            check(paid.size()==1&&secret.equals(keyField.get(paid.get(0))),"ZCode independent paid key never becomes the separate gift JWT");
        }
        List<JSONObject> slots=AccountPoolPolicy.routingAccounts(merged.config.getJSONArray("providers").getJSONObject(0));check(slots.size()==1&&secret.equals(slots.get(0).getString("token")),"route uses correct standalone key");
        for(String flag:new String[]{"enabled","off"}) {JSONObject disabled=new JSONObject(input.toString());disabled.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getJSONObject(0).put(flag,"off".equals(flag));AccountTransferCodec.Document dd=AccountTransferCodec.parse(disabled.toString().getBytes(StandardCharsets.UTF_8),provider);JSONObject c=AccountTransferPlan.merge(config,dd,Collections.singleton(0),false,System.currentTimeMillis()).config;check(AccountPoolPolicy.routingAccounts(c.getJSONArray("providers").getJSONObject(0)).isEmpty(),"disabled imported key is not routed");check(RoutingSetupOfflineTest.candidates(RoutingSetupOfflineTest.server(c),provider+"/m").isEmpty(),"actual gateway excludes disabled independent key");}
        JSONObject mixed=merged.config.getJSONArray("providers").getJSONObject(0);mixed.getJSONArray("keys").put(secret);check(AccountPoolPolicy.routingAccounts(mixed).size()==1,"string/object duplicates collapse");
        java.util.List<?> dedup=RoutingSetupOfflineTest.candidates(RoutingSetupOfflineTest.server(merged.config),provider+"/m");
        check(dedup.size()==1&&secret.equals(keyField.get(dedup.get(0))),"actual route deduplicates string/object keys");
        JSONObject legacy=new JSONObject(merged.config.toString());legacy.getJSONArray("providers").getJSONObject(0).put("keys",new JSONArray().put(secret));
        java.util.List<?> legacyRoutes=RoutingSetupOfflineTest.candidates(RoutingSetupOfflineTest.server(legacy),provider+"/m");
        check(legacyRoutes.size()==1&&secret.equals(keyField.get(legacyRoutes.get(0))),"original string key route remains valid");
        mixed.put("accounts",new JSONArray().put(new JSONObject().put("id","disabled-account").put("access",secret).put("enabled",false)));check(AccountPoolPolicy.routingAccounts(mixed).isEmpty(),"disabled account mirror not revived through keys");
        check(RoutingSetupOfflineTest.candidates(RoutingSetupOfflineTest.server(merged.config),provider+"/m").isEmpty(),"actual route does not revive disabled account through its key mirror");
    }
    static final class Generated extends InputStream {
        int left,read;boolean closed;Generated(int n){left=n;}
        public int read(){if(left==0)return -1;left--;read++;return 'x';}
        public int read(byte[] b,int off,int len){if(left==0)return -1;int n=Math.min(left,len);Arrays.fill(b,off,off+n,(byte)'x');left-=n;read+=n;return n;}
        public void close(){closed=true;}
    }
    static void boundedAuth(String name)throws Exception {
        Class<?> c="antigravity".equals(name)?AntigravityAuth.class:ZCodeAuth.class;Method m=c.getDeclaredMethod("readAll",InputStream.class);m.setAccessible(true);
        Generated input=new Generated(8*1024*1024+1);boolean rejected=false;try{invoke(m,null,input);}catch(IOException expected){rejected=true;}
        check(rejected,name+" control response must reject an oversized stream");check(input.closed,name+" rejected stream is closed");check(input.read<=8*1024*1024+8192,"bounded read does not consume an arbitrary body");
        String text="{\"message\":\"中文正常\"}";check(text.equals(invoke(m,null,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))),"valid Chinese response unchanged");
    }
    static Object nativeBridge()throws Exception {
        Field u=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");u.setAccessible(true);sun.misc.Unsafe unsafe=(sun.misc.Unsafe)u.get(null);
        Object activity=unsafe.allocateInstance(MainActivity.class);Class<?> c=Class.forName("com.gaeul.allin.MainActivity$NativeBridge");Object bridge=unsafe.allocateInstance(c);Field outer=c.getDeclaredField("this$0");outer.setAccessible(true);outer.set(bridge,activity);return bridge;
    }
    static JSONObject nativeRequest(String raw)throws Exception {
        // Binding first proves this fixture owns the destination; never fall back to a running service.
        try(ServerSocket listener=new ServerSocket()) {
            listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(5000);AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread server=new Thread(()->{try(Socket s=listener.accept()){s.setSoTimeout(5000);InputStream in=s.getInputStream();int a=-1,b=-1,c=-1,v;while((v=in.read())!=-1){if(a==13&&b==10&&c==13&&v==10)break;a=b;b=c;c=v;}s.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));s.getOutputStream().flush();}catch(Throwable e){failure.set(e);}},"synthetic-http-peer");server.start();
            Object bridge=nativeBridge();Method request=bridge.getClass().getDeclaredMethod("doRequest",String.class,String.class,String.class);request.setAccessible(true);
            JSONObject reply=new JSONObject((String)invoke(request,bridge,"GET","/api/health",""));ConfigConsistencyOfflineTest.join(server);if(failure.get()!=null)throw new AssertionError(failure.get());return reply;
        }
    }
    static void nativeFraming(String kind)throws Exception {
        String wire;
        switch(kind){case "length":wire="HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{}";break;case "chunk":wire="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8\r\n{}";break;case "terminal":wire="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n";break;case "ambiguous":wire="HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n";break;default:wire="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\nX-Trailer: incomplete\r\n";}
        JSONObject r=nativeRequest(wire);check(r.getInt("status")==0,"MainActivity must not report HTTP 200 for "+kind+" corruption");check(r.optString("body").isEmpty(),"no partial successful body");
    }
    static int run(String label,ConfigConsistencyOfflineTest.Test test){return ConfigConsistencyOfflineTest.run(label,test);}
    public static void main(String[] args)throws Exception {
        TestPrivacyScope.initialize();int failed=0;String group=args.length==0?"all":args[0];
        if("all".equals(group)||"refresh".equals(group))for(String p:new String[]{"cline","codex","trae-cn"})failed+=run(p+" unrelated update",()->siblingRefresh(p));
        if("all".equals(group)||"keys".equals(group))for(String p:new String[]{"workbuddy","workbuddy-ai","zcode"})failed+=run(p+" imported key",()->importedKey(p));
        if("all".equals(group)||"responses".equals(group)){for(String p:new String[]{"antigravity","zcode"})failed+=run(p+" response bound",()->boundedAuth(p));for(String k:new String[]{"length","chunk","terminal","ambiguous","trailer"})failed+=run("native "+k,()->nativeFraming(k));}
        if(failed>0)throw new AssertionError(failed+" boundary scenarios failed; "+checks+" checks evaluated");System.out.println("AdvisoryBoundaryOfflineTest PASS: "+checks+" checks");
    }
}
