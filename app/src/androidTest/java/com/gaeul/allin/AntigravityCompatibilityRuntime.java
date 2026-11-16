package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Android ART checks with synthetic byte transports; never authenticates or contacts Google. */
final class AntigravityCompatibilityRuntime {
    private int checks;
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int run()throws Exception{return new AntigravityCompatibilityRuntime().verify();}
    private int verify()throws Exception {
        AntigravityAuth.Flow flow=AntigravityAuth.createFlow();
        String consent=java.net.URLDecoder.decode(flow.authUrl,"UTF-8");
        check(consent.contains("https://www.googleapis.com/auth/aicode"),"Android consent URL requests aicode");
        check(consent.contains("code_challenge_method=S256")&&consent.contains("prompt=consent"),"Android PKCE and explicit consent remain");
        check(flow.state.length()>10&&flow.verifier.length()>30,"Android state/verifier remain generated");
        check(consent.contains("http://localhost:1456/oauth-callback"),"existing callback is retained");
        JSONObject payload=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic Android request")));
        JSONObject info=new JSONObject().put("modelEnum","MODEL_ANDROID_FIXTURE").put("modelProvider","MODEL_PROVIDER_GOOGLE");
        JSONObject envelope=AntigravityClient.buildEnvelope(payload,"gemini-3.8-flash","synthetic-project",info);
        JSONObject next=AntigravityClient.buildEnvelope(payload,"gemini-3.8-flash","synthetic-project",info);
        JSONObject labels=envelope.getJSONObject("request").getJSONObject("labels");
        check(envelope.optString("requestId").startsWith("agent/"),"Android requestId contract");
        check(labels.optString("cascade_id").equals(next.getJSONObject("request").getJSONObject("labels").optString("cascade_id")),"Android stable conversation");
        check("MODEL_ANDROID_FIXTURE".equals(labels.optString("model_enum")),"Android model enum");
        check("false".equals(labels.optString("used_non_gemini_model")),"Android provider flag");
        String raw=envelope.toString();int at=-1;
        for(String k:new String[]{"project","requestId","request","model","userAgent","requestType"}){
            int n=raw.indexOf(JSONObject.quote(k)+":");check(n>at,"Android serialized order "+k);at=n;
        }
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();final int[] opens={0};
        AntigravityAuth.Transport wire=(url,host,port,connect,read)->{
            opens[0]++;return new AntigravityAuth.Link(){
                public InputStream in(){return new ByteArrayInputStream("HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));}
                public OutputStream out(){return sent;}public void close(){}
            };
        };
        AntigravityAuth.Dispatch response=AntigravityAuth.dispatch(new String[]{"https://fixture-one.invalid","https://fixture-two.invalid"},
                AntigravityAuth.STREAM_PATH,"SYNTHETIC_ANDROID",raw.getBytes(StandardCharsets.UTF_8),1000,1000,wire,AntigravityAuth.Cancellation.NONE);
        try{check(response.resp.status==403&&opens[0]==1,"Android 403 has no replay");}finally{if(response.resp!=null)response.resp.close();}
        String head=sent.toString("UTF-8");check(head.contains("aidev_client; os_type=linux;")&&head.contains("cl=1021296516"),"actual Android wire UA");
        check(!head.contains("Accept: text/event-stream"),"actual Android wire removes old Accept");
        check(head.endsWith(raw),"Android wire body unchanged");
        JSONObject account=new JSONObject().put("access","ANDROID_ACCESS_SENTINEL").put("refresh","ANDROID_REFRESH_SENTINEL");
        String json=new JSONObject().put("error",new JSONObject().put("code",403).put("status","PERMISSION_DENIED")
                .put("message","Request had insufficient authentication scopes. ANDROID_ACCESS_SENTINEL ANDROID_REFRESH_SENTINEL")
                .put("details",new JSONArray().put(new JSONObject().put("@type","type.googleapis.com/google.rpc.ErrorInfo").put("reason","ACCESS_TOKEN_SCOPE_INSUFFICIENT")))).toString();
        JSONObject result=new JSONObject();AntigravityProtocol.describeRefusal(result,403,AntigravityProtocol.safeError(json,account));
        check("授权范围".equals(result.optString("failureLayerLabel")),"Android scope diagnostic");
        check(!result.toString().contains("ANDROID_ACCESS_SENTINEL")&&!result.toString().contains("ANDROID_REFRESH_SENTINEL"),"Android errors redact credential echoes");
        check(result.getJSONObject("upstreamError").getJSONArray("reasons").length()==1,"Android structured ErrorInfo");
        check("free-tier".equals(AntigravityProtocol.onboardBody(null).optString("tierId")),"Android new onboard fields");
        return checks;
    }
}
