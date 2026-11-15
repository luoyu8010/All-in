package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real gateway HTTP: a supplier deliberately never finishes until after the list is returned. */
public final class GatewayModelsNonBlockingHttpTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static final class Upstream implements ZCodeGiftControl.Transport {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(), claims = new AtomicInteger();
        volatile boolean fail;
        public JSONObject summary(String jwt, String device) throws Exception {
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture was not released");
            if (fail) return new JSONObject().put("ok",false).put("complete",false).put("error","fixture unavailable");
            return new JSONObject().put("ok",true).put("hasGift",true).put("remainingUnits",5)
                    .put("models",new JSONArray().put("gift-model"))
                    .put("claimable",new JSONArray())
                    .put("buckets",new JSONArray().put(new JSONObject().put("remaining",5)
                            .put("capabilities",new JSONArray().put("model:gift-model"))));
        }
        public JSONObject claim(String jwt, String device, String id) { claims.incrementAndGet(); throw new AssertionError("listing cannot claim"); }
    }
    static JSONObject config() {
        return new JSONObject().put("server",new JSONObject().put("authKey","fixture-model-list"))
                .put("providers",new JSONArray()
                    .put(new JSONObject().put("id","healthy").put("protocol","openai").put("enabled",true)
                        .put("keys",new JSONArray().put("fixture-healthy-key")).put("models",new JSONArray().put("ready")))
                    .put(new JSONObject().put("id","zcode").put("protocol","openai").put("enabled",true)
                        .put("keys",new JSONArray().put("fixture-paid-key"))
                        .put("accounts",new JSONArray().put(new JSONObject().put("id","gift-user").put("enabled",true)
                            .put("jwt","fixture-gift-jwt").put("device","fixture-device").put("plan","")))
                        .put("models",new JSONArray().put("gift-model").put("paid-only"))));
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer backend;
        Gateway(JSONObject config) throws Exception { super("127.0.0.1",0); backend=RoutingSetupOfflineTest.server(config); start(SOCKET_READ_TIMEOUT,true); }
        public Response serve(IHTTPSession session) { return backend.serve(session); }
        protected boolean useGzipWhenAccepted(Response response) { return backend.useGzipWhenAccepted(response); }
        public void close() { super.stop(); backend.stop(); }
    }
    static final class Reply { int status; JSONObject body; }
    static Reply get(Gateway gateway, String path, String key) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();
        c.setConnectTimeout(1000); c.setReadTimeout(1200); c.setRequestProperty("Connection","close");
        if(key!=null)c.setRequestProperty("Authorization","Bearer "+key);
        try {
            Reply result=new Reply(); result.status=c.getResponseCode();
            try(InputStream in=result.status>=400?c.getErrorStream():c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
                result.body=new JSONObject(bytes.toString("UTF-8"));
            }
            return result;
        } finally {c.disconnect();}
    }
    static Set<String> ids(Reply reply) {
        Set<String> out=new HashSet<>();JSONArray rows=reply.body.optJSONArray("data");
        for(int i=0;rows!=null&&i<rows.length();i++)out.add(rows.getJSONObject(i).getString("id"));return out;
    }
    public static void main(String[] args) throws Exception {
        Upstream upstream=new Upstream();ZCodeGiftControl.setTestHooks(null,upstream);
        try(Gateway gateway=new Gateway(config())) {
            check(get(gateway,"/v1/models",null).status==401,"unauthorized callers still rejected");
            check(upstream.calls.get()==0,"unauthorized listing starts no upstream lookup");
            Reply cold;
            try {cold=get(gateway,"/v1/models","fixture-model-list");}
            catch(SocketTimeoutException blocked) {throw new AssertionError("model list waited for the deliberately blocked supplier",blocked);}
            check(cold.status==200,"cold list returns while upstream is blocked");
            check(ids(cold).contains("healthy/ready"),"unrelated provider survives cold lookup");
            check(!ids(cold).contains("zcode/paid-only"),"unknown gift does not advertise paid-only as usable");
            check(!ids(cold).contains("zcode/gift-model"),"unconfirmed gift is not invented");
            check(cold.body.optJSONArray("unconfirmed_providers")!=null,"cold list names unconfirmed supplier");
            check(upstream.entered.await(1,TimeUnit.SECONDS),"background read actually starts");
            for(int i=0;i<8;i++)check(ids(get(gateway,"/v1/models","fixture-model-list")).contains("healthy/ready"),"repeat list remains responsive "+i);
            check(upstream.calls.get()==1,"concurrent cache misses are coalesced");
            check(upstream.claims.get()==0,"metadata lookups cannot claim");
            upstream.release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(ZCodeGiftControl.peekSummary("fixture-gift-jwt","fixture-device")==null&&System.nanoTime()<until)Thread.sleep(5);
            Reply warm=get(gateway,"/v1/models","fixture-model-list");
            check(ids(warm).contains("zcode/gift-model"),"confirmed gift appears on next list");
            check(!ids(warm).contains("zcode/paid-only"),"warm gift keeps paid isolation");
            check(upstream.calls.get()==1,"fresh cache performs no second upstream read");
            check(!warm.body.has("unconfirmed_providers"),"confirmed list clears unknown marker");
            JSONObject filtered=config().put("exposed",new JSONObject().put("enabled",true)
                    .put("items",new JSONArray().put("healthy/ready")));
            GatewayReleaseHardeningOfflineTest.setField(gateway.backend,"configJson",filtered);
            Set<String> visible=ids(get(gateway,"/v1/models","fixture-model-list"));
            check(visible.size()==1&&visible.contains("healthy/ready"),"exposed whitelist retained");
            JSONObject disabled=config();disabled.getJSONArray("providers").getJSONObject(1).put("enabled",false);
            GatewayReleaseHardeningOfflineTest.setField(gateway.backend,"configJson",disabled);
            Reply hidden=get(gateway,"/v1/models","fixture-model-list");
            check(ids(hidden).size()==1&&!hidden.body.has("unconfirmed_providers"),"disabled supplier not refreshed or exposed");
            Reply all=get(gateway,"/v1/models?all=1","fixture-model-list");
            check(ids(all).contains("zcode/paid-only"),"explicit all retains diagnostic configured-model view");
            check(upstream.calls.get()==1,"filtering and all-mode add no upstream work");
            JSONObject switched=config();switched.getJSONArray("providers").getJSONObject(1)
                    .getJSONArray("accounts").getJSONObject(0).put("jwt","fixture-new-session");
            GatewayReleaseHardeningOfflineTest.setField(gateway.backend,"configJson",switched);
            Reply changed=get(gateway,"/v1/models","fixture-model-list");
            check(!ids(changed).contains("zcode/gift-model"),"new credentials cannot reuse old cached entitlement");
            check(ids(changed).contains("healthy/ready"),"account change still leaves unrelated models available");

            System.out.println("GatewayModelsNonBlockingHttpTest PASS "+checks+" checks");
        } finally {
            upstream.release.countDown();
            long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(true) {
                try {ZCodeGiftControl.setTestHooks(null,null);break;}
                catch(IllegalStateException stillFinishing) {
                    if(System.nanoTime()>=cleanupDeadline)throw stillFinishing;
                    Thread.sleep(10);
                }
            }
        }
    }
}
