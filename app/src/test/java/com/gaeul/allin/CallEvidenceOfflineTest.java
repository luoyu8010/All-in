package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.lang.reflect.Method;
import java.util.*;

/** Privacy, bounded persistence and immutability of the optional diagnostic extension. */
public final class CallEvidenceOfflineTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static final String ID="12345678-1234-4234-8234-123456789012";
    static JSONObject shape(){return new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","SECRET_PROMPT"))
            .put(new JSONObject().put("role","user").put("content","SECRET_USER"))).put("tools",new JSONArray().put(new JSONObject().put("name","SECRET_TOOL").put("parameters","SECRET_ARGS"))).put("reasoning_effort","high");}
    public static void main(String[] args)throws Exception{
        check(CallEvidence.entry("/untrusted?token=SECRET","POST",ID)==null,"arbitrary URL is not recorded");
        check(CallEvidence.entry("/v1/chat/completions","POST","SECRET_TOKEN")==null,"caller-controlled non-UUID ID not accepted");
        check(CallEvidence.entry("/v1/chat/completions","DELETE",ID)==null,"only inference POST diagnostics");
        for(String path:CallEvidence.PATHS){
            CallEvidence.Builder b=new CallEvidence.Builder(CallEvidence.entry(path,"POST",ID),"antigravity",new JSONObject().put("id","PRIVATE_ACCOUNT_ID").put("access","SECRET_ACCESS"));
            b.shape(shape(),true);b.dispatched("gemini-3.8-flash-high",1);b.reply(429,"58","{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"SECRET_ERROR\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"RATE_LIMIT_EXCEEDED\",\"metadata\":{\"token\":\"SECRET_METADATA\"}}]}}");
            CallEvidence.Snapshot snapshot=b.snapshot();JSONObject j=snapshot.json();
            check(j.getInt("physicalSends")==1&&j.getLong("upstreamRetryAfterSeconds")==58,"actual sends and seconds recorded");
            check(j.optInt("toolCount")==1&&j.optBoolean("systemPresent")&&j.optInt("messageCount")==2,"counts instead of content");
            check("RESOURCE_EXHAUSTED".equals(j.optString("googleStatus"))&&"RATE_LIMIT_EXCEEDED".equals(j.optString("googleReason")),"whitelisted rejection enums");
            check(!j.toString().contains("SECRET_")&&!j.toString().contains("PRIVATE_ACCOUNT_ID"),"no payload, upstream text or identity material");
            String frozen=j.toString();j.put("path","evil");b.reply(200,"0",null);b.dispatched("other",1);check(snapshot.json().toString().equals(frozen),"immutable after freeze and independent JSON views");
            check(CallEvidence.Snapshot.parse(snapshot.json()).json().toString().equals(frozen),"validated round trip");
            UsageLedger.Row row=new UsageLedger.Row();row.requestId=ID;row.callEvidence=snapshot;row.status=429;
            Method encode=UsageLedger.class.getDeclaredMethod("toJsonLine",UsageLedger.Row.class);encode.setAccessible(true);
            Method decode=UsageLedger.class.getDeclaredMethod("parse",String.class);decode.setAccessible(true);
            UsageLedger.Row restored=(UsageLedger.Row)decode.invoke(null,(String)encode.invoke(null,row));
            check(restored.callEvidence!=null&&restored.callEvidence.json().toString().equals(frozen),"actual ledger persistence restores evidence");
            Method estimate=UsageLedger.class.getDeclaredMethod("estimate",UsageLedger.Row.class);estimate.setAccessible(true);
            UsageLedger.Row baseline=new UsageLedger.Row();check((Long)estimate.invoke(null,row)>(Long)estimate.invoke(null,baseline),"ledger memory budget includes evidence");
        }
        CallEvidence.Builder origin=new CallEvidence.Builder(CallEvidence.entry("/v1/messages","POST",ID),"antigravity",new JSONObject().put("id","private"));origin.shape(shape(),false);origin.dispatched("gemini-3.8-flash-high",1);origin.reply(429,null,"{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
        CallEvidence.Builder local=new CallEvidence.Builder(CallEvidence.entry("/api/test-model","POST",UUID.randomUUID().toString()),"antigravity",new JSONObject().put("id","private"));local.cooldown(58000,origin.snapshot());JSONObject j=local.snapshot().json();
        check(j.optLong("localCooldownRemainingMs")==58000&&j.isNull("upstreamRetryAfterSeconds"),"local milliseconds not mislabeled as upstream seconds");
        check(j.isNull("sentModel")&&j.optInt("physicalSends")==0,"local block never claims an actual model send");
        check(ID.equals(j.optString("cooldownOriginRequestId"))&&j.optInt("cooldownOriginStatus")==429,"origin correlation retained");
        check("RESOURCE_EXHAUSTED".equals(j.optString("cooldownOriginGoogleStatus")),"origin rejection type retained");
        check(j.isNull("cooldownOriginRetryAfterSeconds"),"unknown origin header remains null");
        JSONObject attack=origin.snapshot().json().put("headers",new JSONObject().put("Authorization","SECRET_HEADER"))
                .put("effort","SECRET_EFFORT").put("accountRef","PRIVATE_ID").put("googleStatus","SECRET_STATUS").put("googleReason","SECRET_REASON")
                .put("messageCount",-1).put("toolCount","one").put("physicalSends",99999).put("localCooldownRemainingMs",Long.MAX_VALUE);
        JSONObject safe=CallEvidence.Snapshot.parse(attack).json();check(!safe.toString().contains("SECRET_")&&!safe.toString().contains("PRIVATE_ID"),"parse re-whitelists unknown or malicious properties");
        for(String field:new String[]{"effort","accountRef","googleStatus","googleReason","messageCount","toolCount","physicalSends","localCooldownRemainingMs"})check(safe.isNull(field),"invalid field unknown: "+field);
        check(CallEvidence.Snapshot.parse(attack.put("schemaVersion",99))==null,"future schema not guessed");
        check(CallEvidence.accountRef("antigravity",new JSONObject())==null,"missing account ID is unknown");
        UsageLedger.Row old=new UsageLedger.Row();CallEvidence.freeze(old);check(old.callEvidence==null,"old rows not backfilled with invented facts");
        GatewayServer gateway=Audit53RegressionTest.gateway();
        try{Method remember=GatewayServer.class.getDeclaredMethod("rememberCooldownEvidence",String.class,CallEvidence.Snapshot.class);remember.setAccessible(true);
            for(int i=0;i<530;i++)remember.invoke(gateway,"synthetic-"+i,origin.snapshot());
            java.lang.reflect.Field f=GatewayServer.class.getDeclaredField("rateCooldownEvidence");f.setAccessible(true);check(((Map<?,?>)f.get(gateway)).size()==512,"optional cause cache capped at 512 without touching rate policy");
        }finally{gateway.stop();}
        System.out.println("CallEvidenceOfflineTest PASS checks="+checks);
    }
}
