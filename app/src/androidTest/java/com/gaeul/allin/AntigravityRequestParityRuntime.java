package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Actual ART conversion and byte transport; synthetic declarations only, never calls Google. */
final class AntigravityRequestParityRuntime {
    int checks;void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int run()throws Exception{return new AntigravityRequestParityRuntime().verify();}
    int verify()throws Exception{
        JSONObject schema=new JSONObject().put("$defs",new JSONObject().put("Position",new JSONObject().put("type","object")
                .put("properties",new JSONObject().put("x",new JSONObject().put("type","integer").put("minimum",0)).put("y",new JSONObject().put("type","integer")))
                .put("required",new JSONArray().put("x").put("y"))))
                .put("type","object").put("properties",new JSONObject().put("point",new JSONObject().put("$ref","#/$defs/Position"))
                        .put("tag",new JSONObject().put("anyOf",new JSONArray().put(new JSONObject().put("type","null")).put(new JSONObject().put("type","string").put("minLength",2))))
                        .put("items",new JSONObject().put("type","array").put("items",new JSONObject().put("type","string")).put("maxItems",20)))
                .put("required",new JSONArray().put("point").put("missing"));
        JSONArray tools=new JSONArray();for(int i=0;i<103;i++)tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","synthetic_tool_"+i).put("parameters",schema)));
        JSONObject payload=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","Only use a declared tool if needed."))
                .put(new JSONObject().put("role","user").put("content","synthetic request"))).put("tools",tools).put("reasoning_effort","high");
        String original=payload.toString();JSONObject envelope=AntigravityClient.buildEnvelope(payload,"gemini-3.8-flash-high","synthetic-project");
        JSONObject req=envelope.getJSONObject("request");JSONArray declarations=req.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        check(declarations.length()==103,"all 103 Android declarations retained");
        for(int i=0;i<103;i++){
            JSONObject p=declarations.getJSONObject(i).getJSONObject("parameters"),props=p.getJSONObject("properties");
            check("object".equals(props.getJSONObject("point").optString("type"))&&props.getJSONObject("point").getJSONArray("required").length()==2,"Android local ref resolved "+i);
            check(props.getJSONObject("tag").optBoolean("nullable")&&props.getJSONObject("tag").optInt("minLength")==2,"Android nullable union and constraints "+i);
            check(p.getJSONArray("required").length()==1&&props.getJSONObject("items").optInt("maxItems")==20,"Android filtered required and array bounds "+i);
        }
        check(payload.toString().equals(original),"Android caller request not mutated");
        check("high".equals(req.getJSONObject("generationConfig").getJSONObject("thinkingConfig").optString("thinkingLevel")),"Android high variant/high config match");
        JSONObject low=AntigravityClient.buildEnvelope(payload,"gemini-3.8-flash-low","synthetic-project").getJSONObject("request");
        check("low".equals(low.getJSONObject("generationConfig").getJSONObject("thinkingConfig").optString("thinkingLevel")),"Android actual low variant takes precedence over conflicting requested high");
        JSONObject xhigh=AntigravityClient.buildEnvelope(payload,"gemini-4-hydrogen-xhigh","synthetic-project").getJSONObject("request");
        check("xhigh".equals(xhigh.getJSONObject("generationConfig").getJSONObject("thinkingConfig").optString("thinkingLevel")),"Android xhigh variant retains its level");
        final int[] opens={0};final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        AntigravityAuth.Transport transport=(url,host,port,connect,read)->{opens[0]++;return new AntigravityAuth.Link(){
            public InputStream in(){return new ByteArrayInputStream("HTTP/1.1 429 Too Many Requests\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));}
            public OutputStream out(){return sent;}public void close(){}
        };};
        AntigravityAuth.Dispatch dispatched=AntigravityAuth.dispatch(new String[]{"https://synthetic-one.invalid","https://synthetic-two.invalid"},AntigravityAuth.STREAM_PATH,"SYNTHETIC_ANDROID_ONLY",envelope.toString().getBytes(StandardCharsets.UTF_8),1000,1000,transport,AntigravityAuth.Cancellation.NONE);
        try{check(dispatched.resp.status==429&&opens[0]==1,"Android 429 does not resend tools to a second base");}finally{if(dispatched.resp!=null)dispatched.resp.close();}
        String wire=sent.toString("UTF-8");JSONObject actual=new JSONObject(wire.substring(wire.indexOf("\r\n\r\n")+4));
        check(actual.getJSONObject("request").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").similar(declarations),"Android transmitted declaration bytes decode to normalized schemas");
        check(!req.getJSONObject("generationConfig").has("maxOutputTokens"),"Android no new Gemini output ceiling");
        return checks;
    }
}
