package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Actual ingress -> Antigravity fake bytes -> ledger -> local cooldown, with no vendor traffic. */
public final class CallEvidenceGatewayTest {
    static int checks,failed;
    static void check(boolean ok,String label){checks++;if(!ok){failed++;System.out.println("FAIL "+label);}}
    static JSONObject serialized(UsageLedger.Row row)throws Exception{
        Method m=UsageLedger.class.getDeclaredMethod("toJsonLine",UsageLedger.Row.class);m.setAccessible(true);return new JSONObject((String)m.invoke(null,row));
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("call-evidence57-");List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<>());
        AntigravityCompatibilityGatewayTest.Wire wire=new AntigravityCompatibilityGatewayTest.Wire("{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}");wire.status=429;
        GatewayServer g=AntigravityCompatibilityGatewayTest.server(dir,wire);
        Audit53RegressionTest.field(GatewayServer.class,"totalTokens").set(g,new java.util.concurrent.atomic.AtomicLong());
        JSONObject cfg=ResponsesGatewayOfflineTest.config(g),meta=cfg.getJSONArray("providers").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("gemini-3.8-flash");
        meta.put("defaultId","gemini-3.8-flash-low").put("variants",new JSONObject().put("low","gemini-3.8-flash-low").put("high","gemini-3.8-flash-high"));
        meta.getJSONObject("variantMetadata").put("gemini-3.8-flash-high",new JSONObject().put("modelEnum","MODEL_HIGH").put("modelProvider","MODEL_PROVIDER_GOOGLE"));
        UsageLedger.setHooks(rows::add);
        try{
            wire.status=200;
            wire.body="data: {"+(char)34+"response"+(char)34+": {"+(char)34+"candidates"+(char)34+":[{"+(char)34+"content"+(char)34+": {"+(char)34+"parts"+(char)34+":[{"+(char)34+"text"+(char)34+":"+(char)34+"OK"+(char)34+"}]},"+(char)34+"finishReason"+(char)34+":"+(char)34+"STOP"+(char)34+"}]}}"+(char)10+(char)10;
            JSONObject ok=AntigravityCompatibilityGatewayTest.probe(g);check(ok.optBoolean("ok"),"ordinary test succeeds in paired scenario: "+ok.toString());
            UsageLedger.Row success=rows.stream().filter(r->r.attempt>0&&"antigravity".equals(r.prov)).findFirst().get();
            JSONObject successFacts=serialized(success).optJSONObject("callEvidence");
            if(successFacts!=null)check("gemini-3.8-flash-low".equals(successFacts.optString("sentModel")),"ordinary test records its default low variant");
            rows.clear();wire.status=429;wire.body=new JSONObject().put("error",new JSONObject().put("code",429).put("message","Resource has been exhausted (e.g. check quota).").put("status","RESOURCE_EXHAUSTED")).toString();
            JSONObject request=new JSONObject().put("model","antigravity/gemini-3.8-flash").put("reasoning_effort","high")
                    .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","SYNTHETIC_PRIVATE_PROMPT"))
                            .put(new JSONObject().put("role","user").put("content","SYNTHETIC_PRIVATE_USER")))
                    .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                            .put("name","SYNTHETIC_PRIVATE_TOOL").put("parameters",new JSONObject().put("type","object")))));
            NanoHTTPD.Response response=Audit53RegressionTest.request(g,"/v1/chat/completions","POST",request.toString());
            check(response.getStatus().getRequestStatus()==429,"initial upstream 429 retained");
            String trace=response.getHeader("X-AllIn-Request-Id");check(trace!=null&&!trace.isEmpty(),"client receives correlation ID");
            Audit53RegressionTest.body(response);
            UsageLedger.Row first=rows.stream().filter(r->r.attempt>0&&"antigravity".equals(r.prov)).findFirst().orElseThrow(()->new AssertionError("attempt row missing"));
            JSONObject firstJson=serialized(first),facts=firstJson.optJSONObject("callEvidence");
            check(facts!=null,"attempt persists call evidence");
            if(facts!=null){
                check("/v1/chat/completions".equals(facts.optString("path")),"actual ingress path");
                check("model-api".equals(facts.optString("entry")),"no unsupported Eta attribution");
                check("gemini-3.8-flash-high".equals(facts.optString("sentModel")),"final high variant rather than family");
                check(facts.optBoolean("systemPresent")&&facts.optInt("toolCount")==1,"normalized request shape only");
                check("high".equals(facts.optString("effort")),"effort enum captured");
                check("upstream".equals(facts.optString("source"))&&facts.optInt("upstreamStatus")==429,"upstream rejection source");
                check(facts.isNull("upstreamRetryAfterSeconds"),"missing upstream header remains unknown");
                check(facts.optString("accountRef").startsWith("acct-"),"account comparison uses a hash reference");
                check(first.requestId.equals(trace),"response header matches ledger ID");
                check(!success.requestId.equals(first.requestId)&&facts.optString("accountRef").equals(successFacts.optString("accountRef")),"successful panel and failed Agent-shaped call have different IDs but comparable account references");
            }
            check(!firstJson.toString().contains("SYNTHETIC_PRIVATE_"),"diagnostics never include prompt or tool name");
            int before=wire.opens;rows.clear();JSONObject probe=AntigravityCompatibilityGatewayTest.probe(g);
            check(wire.opens==before,"probe while cooling sends no new request");
            check("本地限流冷却".equals(probe.optString("failureLabel")),"cooldown displayed separately from budget rejection");
            check(!probe.optString("advice").contains("预算"),"cooldown advice does not blame budget");
            UsageLedger.Row local=rows.stream().filter(r->r.attempt>0&&"antigravity".equals(r.prov)).findFirst().orElseThrow(()->new AssertionError("local row missing"));
            JSONObject localFacts=serialized(local).optJSONObject("callEvidence");check(localFacts!=null,"local cooldown persists evidence");
            if(localFacts!=null){
                check("/api/test-model".equals(localFacts.optString("path"))&&"panel-test".equals(localFacts.optString("entry")),"panel request path separate");
                check("gateway_cooldown".equals(localFacts.optString("source")),"local cooldown source explicit");
                check(localFacts.optLong("localCooldownRemainingMs")>0&&localFacts.optLong("localCooldownRemainingMs")<=60000,"local wait explicitly milliseconds");
                check(localFacts.isNull("sentModel")&&localFacts.optInt("physicalSends")==0,"blocked call has no claimed upstream variant or send");
                check(first.requestId.equals(localFacts.optString("cooldownOriginRequestId")),"cooldown correlated to initial upstream rejection");
                check(localFacts.optString("accountRef").equals(facts.optString("accountRef")),"same account can be compared without exposing identity");
            }
            check(!local.requestId.isEmpty()&&local.requestId.equals(probe.optString("requestId")),"panel response also has request ID");
            rows.clear();
            AntigravityCompatibilityGatewayTest.Wire heldWire=new AntigravityCompatibilityGatewayTest.Wire(AntigravityCompatibilityGatewayTest.error("Permission denied.","SYNTHETIC_PERMISSION"));
            GatewayServer heldGateway=AntigravityCompatibilityGatewayTest.server(dir,heldWire);
            ResponsesGatewayOfflineTest.config(heldGateway).put("resilience",new JSONObject().put("heartbeat",true).put("patienceMillis",0));
            NanoHTTPD listener=new NanoHTTPD("127.0.0.1",0){public Response serve(IHTTPSession session){return heldGateway.serve(session);}};
            listener.start(5000,true);
            try{
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+listener.getListeningPort()+"/v1/chat/completions").openConnection();
                connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Authorization","Bearer SYNTHETIC_INFERENCE");connection.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream output=connection.getOutputStream()){output.write(request.put("stream",true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                int status=connection.getResponseCode();String header=connection.getHeaderField("X-AllIn-Request-Id");
                java.io.InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();if(input!=null)try(java.io.InputStream in=input){while(in.read()!=-1){}}
                connection.disconnect();
                UsageLedger.Row held=rows.stream().filter(r->r.attempt>0&&"antigravity".equals(r.prov)).findFirst().get();
                JSONObject h=serialized(held).getJSONObject("callEvidence");
                check(status==403&&header!=null&&header.equals(held.requestId),"held HTTP preserves ID through worker handoff");
                check("/v1/chat/completions".equals(h.optString("path"))&&h.optBoolean("stream"),"held worker retains correct ingress and shape");
                check(heldWire.opens==1,"held rejection still sends once");
            }finally{listener.stop();heldGateway.stop();}
            System.out.println("CallEvidenceGatewayTest checks="+checks+" failures="+failed);
            if(failed>0)throw new AssertionError("diagnostic contract failures="+failed);
        }finally{g.stop();UsageLedger.setHooks(null);try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception e){throw new RuntimeException(e);}});}}
    }
}
