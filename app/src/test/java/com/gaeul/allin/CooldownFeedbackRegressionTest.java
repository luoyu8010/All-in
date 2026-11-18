package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import fi.iki.elonen.NanoHTTPD;

public final class CooldownFeedbackRegressionTest {
    static int checks;static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static String read(InputStream in)throws Exception {try(InputStream stream=in){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=stream.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");}}
    static class Conn extends HttpURLConnection {
        final boolean stream;final String retry;final AtomicCalls count;final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Conn(String u,boolean stream,String retry,AtomicCalls count)throws Exception{super(new URL(u));this.stream=stream;this.retry=retry;this.count=count;}
        boolean wrote;
        public void connect(){throw new AssertionError("real upstream prohibited");}public void disconnect(){}public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return new FilterOutputStream(sent){public void write(int b)throws IOException{if(!wrote){wrote=true;count.n++;}out.write(b);}public void write(byte[] b,int off,int len)throws IOException{if(len>0&&!wrote){wrote=true;count.n++;}out.write(b,off,len);}};}
        public int getResponseCode(){return stream?200:429;}
        public String getHeaderField(String name){return "Retry-After".equalsIgnoreCase(name)?retry:"Content-Type".equalsIgnoreCase(name)?(stream?"text/event-stream":"application/json"):null;}
        public InputStream getInputStream(){return new ByteArrayInputStream((stream?"data: {\"error\":{\"code\":\"user_model_rate_limited\",\"message\":\"fixture rate refusal\"}}\n\n":"{\"error\":{\"code\":\"user_model_rate_limited\",\"message\":\"fixture rate refusal\"}}").getBytes(StandardCharsets.UTF_8));}
        public InputStream getErrorStream(){return getInputStream();}
    }
    static final class AtomicCalls {int n;}
    static GatewayServer fixture(String id,boolean stream,String retry,AtomicCalls calls)throws Exception {
        JSONObject a=new JSONObject().put("id",id).put("uid","u-"+id).put("access","fixture-token-"+id).put("token","fixture-token-"+id).put("enabled",true).put("expires",System.currentTimeMillis()+3600000);
        JSONObject p=new JSONObject().put("id","raccoon").put("enabled",true).put("baseUrl","https://xiaohuanxiong.com/v1").put("protocol","openai").put("models",new JSONArray().put("fixture-model")).put("keys",new JSONArray().put(a.getString("token"))).put("accounts",new JSONArray().put(a));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-inference")).put("providers",new JSONArray().put(p));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);server.setVendorConnectionsForTests(url->{try{return new Conn(url,stream,retry,calls);}catch(Exception e){throw new IOException(e);}});return server;
    }
    static NanoHTTPD.Response call(GatewayServer s,String path,boolean stream)throws Exception {
        JSONObject body=new JSONObject().put("model","raccoon/fixture-model").put("stream",stream).put("max_tokens",16).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture only")));
        if("/v1/responses".equals(path)){body.remove("messages");body.remove("max_tokens");body.put("input","fixture only").put("max_output_tokens",16);}
        return s.serve(RoutingSetupOfflineTest.request("127.0.0.1",path,"POST",body.toString(),"fixture-inference"));
    }
    static void gateway()throws Exception {
        AtomicCalls calls=new AtomicCalls();GatewayServer s=fixture("http-a",false,"90",calls);NanoHTTPD.Response r=call(s,"/v1/chat/completions",false);String raw=read(r.getData());JSONObject error=new JSONObject(raw).getJSONObject("error");
        check(r.getStatus().getRequestStatus()==429,"received HTTP rejection status");check(error.getLong("retry_after")==90&&error.getString("message").contains("90 秒"),"HTTP body visible wait");check(r.getHeader("Retry-After").equals("90"),"header and body agree");check(error.getString("code").equals("user_model_rate_limited"),"upstream throughput code preserved");check(!raw.contains("fixture-token"),"no credential in error");check(calls.n==1,"one upstream attempt");
        r=call(s,"/v1/chat/completions",false);raw=read(r.getData());check(r.getStatus().getRequestStatus()==429&&new JSONObject(raw).getJSONObject("error").getLong("retry_after")>0,"remaining cooldown on next request");check(calls.n==1,"same-account cooldown sends nothing upstream");
        for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"}) {
            AtomicCalls n=new AtomicCalls();GatewayServer stream=fixture("stream-"+path.replace('/','-'),true,"75",n);r=call(stream,path,true);raw=read(r.getData());
            check(r.getStatus().getRequestStatus()==200,"started stream keeps committed HTTP status "+path);check(raw.contains("75 秒")&&raw.contains("error"),"protocol-visible stream cooldown "+path);check(!raw.contains("[DONE]"),"rate error is not successful completion "+path);check(n.n==1,"one original stream attempt "+path);
        }
    }
    static void raccoonAccountRouting() throws Exception {
        for (String mode : new String[]{"rotate", "usage", "pace"}) {
            for (boolean accountWide : new boolean[]{false, true}) raccoonAccountRouting(mode, accountWide);
        }
    }

    static void raccoonAccountRouting(String mode, boolean accountWide) throws Exception {
        JSONObject provider = new JSONObject().put("id", "raccoon").put("enabled", true)
                .put("baseUrl", "https://xiaohuanxiong.com/v1").put("protocol", "openai").put("accountRouting", mode)
                .put("models", new JSONArray().put("fixture-model").put("other-model"));
        JSONArray accounts = new JSONArray(), keys = new JSONArray();
        for (String id : new String[]{"a", "b"}) {
            String token = "fixture-token-" + mode + "-" + accountWide + "-" + id;
            accounts.put(new JSONObject().put("id", mode + "-" + accountWide + "-" + id).put("uid", id).put("access", token).put("token", token)
                    .put("enabled", true).put("expires", System.currentTimeMillis() + 3600000));
            keys.put(token);
        }
        provider.put("accounts", accounts).put("keys", keys);
        JSONObject config = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-inference"))
                .put("providers", new JSONArray().put(provider));
        GatewayServer gateway = RoutingSetupOfflineTest.server(config);
        List<String> sentAccounts = new ArrayList<>();
        AtomicCalls calls = new AtomicCalls();
        gateway.setVendorConnectionsForTests(url -> {
            try {
                return new Conn(url, false, null, calls) {
                    boolean rejected;
                    int status;
                    String hint;
                    @Override public int getResponseCode() {
                        if (status != 0) return status;
                        String auth = getRequestProperty("Authorization");
                        String model = new JSONObject(new String(sentBytes(), StandardCharsets.UTF_8)).optString("model");
                        String id = auth.endsWith("-a") ? "a" : "b";
                        boolean first = sentAccounts.isEmpty();
                        sentAccounts.add(id);
                        rejected = !"other-model".equals(model) && (first || sentAccounts.size() == 5);
                        hint = first ? "120" : "30";
                        status = rejected ? 429 : 200;
                        return status;
                    }
                    byte[] sentBytes() { return super.sent.toByteArray(); }
                    @Override public String getHeaderField(String name) {
                        return "Retry-After".equalsIgnoreCase(name) && rejected ? hint : super.getHeaderField(name);
                    }
                    @Override public InputStream getInputStream() {
                        if (rejected) {
                            return new ByteArrayInputStream(new JSONObject().put("error", new JSONObject()
                                    .put("code", accountWide ? "user_model_rate_limited" : "rate_limit_error"))
                                    .toString().getBytes(StandardCharsets.UTF_8));
                        }
                        return new ByteArrayInputStream(VendorPolicyGatewayHttpTest.CHAT.getBytes(StandardCharsets.UTF_8));
                    }
                };
            } catch (Exception e) { throw new IOException(e); }
        });
        NanoHTTPD.Response r = call(gateway, "/v1/chat/completions", false);
        check(r.getStatus().getRequestStatus() == 429 && sentAccounts.size() == 1, mode + " preserves one send on first upstream 429: status=" + r.getStatus().getRequestStatus()
                + ", attempts=" + sentAccounts.size() + ", physical=" + calls.n);
        check("120".equals(r.getHeader("Retry-After")), mode + " preserves upstream wait");
        r.close();
        String cooling = sentAccounts.get(0);
        for (int i = 0; i < 3; i++) {
            r = call(gateway, "/v1/chat/completions", false);
            check(r.getStatus().getRequestStatus() == 200, mode + " selects the remaining live account on every request");
            r.close();
        }
        check(sentAccounts.size() == 4 && !sentAccounts.subList(1, 4).contains(cooling), mode + " never sends to the cooling account");
        r = call(gateway, "/v1/chat/completions", false);
        check(r.getStatus().getRequestStatus() == 429 && sentAccounts.size() == 5, mode + " second account now cooling");
        r.close();
        for (String path : new String[]{"/v1/chat/completions", "/v1/messages", "/v1/responses"}) {
            r = call(gateway, path, false);
            JSONObject error = new JSONObject(read(r.getData())).getJSONObject("error");
            long wait = error.optLong("retry_after");
            check(r.getStatus().getRequestStatus() == 429 && wait > 0 && wait <= 30
                    && "gateway".equals(error.optString("cooldown_source")), mode + " earliest local wait in " + path);
            r.close();
        }
        check(calls.n == 5 && sentAccounts.size() == 5, mode + " all cooling means zero additional sends");
        JSONObject input = new JSONObject().put("model", "raccoon/other-model").put("max_tokens", 16)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "fixture")));
        r = gateway.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/v1/chat/completions", "POST", input.toString(), "fixture-inference"));
        check(r.getStatus().getRequestStatus() == (accountWide ? 429 : 200) && calls.n == (accountWide ? 5 : 6),
                mode + " another model respects only explicit credential-wide holds");
        r.close();
    }
    public static void main(String[] args)throws Exception {
        long now=1794564000000L;
        check(CooldownFeedback.limited(429,"{}"),"HTTP rate evidence");check(!CooldownFeedback.limited(0,"connection reset: rate limit maybe"),"network failure not guessed as rate");check(!CooldownFeedback.limited(200,"{\"choices\":[{\"message\":{\"content\":\"rate limit\"}}]}"),"generated content not treated as rejection");
        check(!CooldownFeedback.limited(429,"{\"error\":{\"code\":\"insufficient_quota\"}}"),"quota is not rate limit");
        check(CooldownFeedback.waitMillis("90",0,now)==90000,"valid upstream seconds");check(CooldownFeedback.waitMillis("bad",0,now)==60000,"honest gateway fallback");check(CooldownFeedback.waitMillis(null,500,now)==500,"known local remaining time is not inflated");check(CooldownFeedback.waitMillis("9999999999999999999999",0,now)==21600000,"overflow header bounded");
        JSONObject d=CooldownFeedback.body("raccoon","{}","90",0,now).getJSONObject("error");check(d.getLong("cooldown_until")==now+90000,"absolute cooldown");check(d.getString("cooldown_source").equals("upstream_hint"),"source not a restoration guarantee");
        String normal="data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n";check(read(new CooldownFeedback.Stream(new ByteArrayInputStream(normal.getBytes(StandardCharsets.UTF_8)),"raccoon",null,null)).equals(normal),"ordinary stream bytes preserved");
        final long[] observed={0};String error="data: {\"error\":\n"+"data: {\"code\":\"user_model_rate_limited\",\"retry_after\":45}}\n\n";
        String output=read(new CooldownFeedback.Stream(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)),"raccoon",null,wait->observed[0]=wait));check(output.contains("45 秒")&&observed[0]==45000,"multiline SSE error preserves explicit wait");check(output.startsWith("event: error\n")&&!output.contains("[DONE]"),"terminal SSE error not fake success");
        InputStream broken=new InputStream(){public int read()throws IOException{throw new IOException("broken socket");}};try{read(new CooldownFeedback.Stream(broken,"raccoon",null,null));throw new AssertionError("network error guessed");}catch(IOException expected){check(true,"transport error not assigned a fabricated cooldown");}
        AnthropicBridge.StreamState state=new AnthropicBridge.StreamState("fixture");List<String> events=state.onChunk(CooldownFeedback.body("raccoon","{}","30",0,now));check(events.size()==1&&events.get(0).contains("rate_limit_error")&&events.get(0).contains("retry_after"),"Anthropic projection retains rate metadata");
        gateway();raccoonAccountRouting();System.out.println("CooldownFeedbackRegressionTest PASS: "+checks+" checks");
    }
}
