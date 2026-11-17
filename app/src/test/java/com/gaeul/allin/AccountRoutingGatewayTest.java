package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.nio.file.*;
import java.util.*;

/** Production configuration, account eligibility and native selection; synthetic credentials only. */
public final class AccountRoutingGatewayTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject reply(NanoHTTPD.Response r)throws Exception {try{JSONObject j=new JSONObject(ResponsesGatewayOfflineTest.read(r.getData()));j.put("_http",r.getStatus().getRequestStatus());return j;}finally{r.close();}}
    static JSONObject view(GatewayServer s)throws Exception{return reply(s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null)));}
    static JSONObject post(GatewayServer s,String id,String mode,String token)throws Exception {
        JSONObject body=new JSONObject().put("providerId",id).put("mode",mode);if(token!=null)body.put("expectedStateToken",token);
        return MultiAccountGatewayOfflineTest.post(s,"/api/providers/account-routing",body);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("account-routing-");
        try {
            String[][] types={{"workbuddy","openai"},{"workbuddy-ai","openai"},{"zcode","openai"},{"codex","codex"},{"custom","openai"},{"catpaw","catpaw"},{"antigravity","antigravity"},{"qoder_cn","qoder"},{"trae-cn","trae"},{"loomy","openai"},{"raccoon","openai"},{"cline","cline"},{"github-copilot","copilot"},{"minimax-code","minimax"},{"deepseek_web","deepseek-web"}};
            for(String[] type:types){
                String model="deepseek-web".equals(type[1])?DeepSeekWeb.MODELS[0]:"m";
                JSONArray accounts=new JSONArray();
                for(int i=0;i<4;i++)accounts.put(MultiAccountGatewayOfflineTest.account(type[0]+i,"uid-"+i,"synthetic-"+type[0]+i).put("type","api").put("source","browser").put("expiresAt",System.currentTimeMillis()+3600000L).put("models",new JSONArray().put(model)));
                JSONObject provider=new JSONObject().put("id",type[0]).put("protocol",type[1]).put("baseUrl","https://example.invalid/v1").put("models",new JSONArray().put(model)).put("accounts",accounts).put("keys",new JSONArray());
                GatewayServer s=MultiAccountGatewayOfflineTest.gateway(MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider)),dir.resolve(type[0]+".json"));
                try {
                    String token=view(s).getJSONObject("providerStateTokens").getString(type[0]);
                    JSONObject saved=post(s,type[0],"usage",token);check(saved.optInt("_http")==200&&saved.optString("mode").equals("usage"),type[0]+" setting saved");
                    check(post(s,type[0],"pace",token).optInt("_http")==409,type[0]+" stale write rejected");
                    AccountRoutingPolicy policy=(AccountRoutingPolicy)MultiAccountGatewayOfflineTest.invoke(s,"accountRouter",new Class[]{});
                    JSONObject current=MultiAccountGatewayOfflineTest.live(s).getJSONArray("providers").getJSONObject(0);
                    JSONArray live=current.getJSONArray("accounts");long now=System.currentTimeMillis();
                    for(int i=0;i<4;i++){
                        AccountRoutingPolicy.Seat seat=(AccountRoutingPolicy.Seat)MultiAccountGatewayOfflineTest.invoke(s,"routingSeat",new Class[]{String.class,JSONObject.class},type[0],live.getJSONObject(i));
                        policy.observe(seat,AccountRoutingPolicyOfflineTest.allowance(100,20+i*20,now+86400000L),now);
                    }
                    Object route=MultiAccountGatewayOfflineTest.target(s,type[0]+"/"+model);
                    check(route!=null&&((JSONObject)MultiAccountGatewayOfflineTest.field(route,"account")).optString("id").equals(type[0]+3),type[0]+" real native selector uses quota policy");
                    live.getJSONObject(3).put("enabled",false);
                    route=MultiAccountGatewayOfflineTest.target(s,type[0]+"/"+model);check(route!=null&&!((JSONObject)MultiAccountGatewayOfflineTest.field(route,"account")).optString("id").equals(type[0]+3),type[0]+" disabled slot cannot win");
                    JSONObject disk=new JSONObject(new String(Files.readAllBytes(dir.resolve(type[0]+".json")),"UTF-8"));
                    check(disk.getJSONArray("providers").getJSONObject(0).optString("accountRouting").equals("usage"),type[0]+" persisted restart setting");
                    check(post(s,type[0],"bad",null).optInt("_http")==400,type[0]+" invalid strategy rejected");
                    GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic failure");});
                    try{check(post(s,type[0],"pace",null).optInt("_http")==500,type[0]+" persistence failure returned");}finally{GatewayServer.setConfigWriteHookForTests(null);}
                    check(current.optString("accountRouting").equals("usage"),type[0]+" no partial publish on failed save");
                    check(!saved.toString().contains("synthetic-"),type[0]+" no secrets in settings response");
                }finally{s.stop();}
            }
        }finally{GatewayServer.setConfigWriteHookForTests(null);MultiAccountGatewayOfflineTest.clean(dir);}
        System.out.println("AccountRoutingGatewayTest PASS: "+checks+" checks");
    }
}
