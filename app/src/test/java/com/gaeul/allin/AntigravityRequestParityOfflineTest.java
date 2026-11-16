package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Independent upstream pure-function outputs, including 103 declarations. Never requests auth or generation. */
public final class AntigravityRequestParityOfflineTest {
    static int checks,failures;
    static void check(boolean ok,String label){checks++;if(!ok){failures++;System.out.println("FAIL "+label);}}
    static boolean equal(Object a,Object b){if(a==b)return true;if(a==null||b==null)return false;if(a instanceof JSONObject&&b instanceof JSONObject)return ((JSONObject)a).similar(b);if(a instanceof JSONArray&&b instanceof JSONArray)return ((JSONArray)a).similar(b);return a.equals(b);}
    static JSONObject payload(String effort){return new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic request"))).put("reasoning_effort",effort);}
    public static void main(String[] args)throws Exception{
        JSONObject oracle=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        check("10cd2ca45fa194a4270d189ec958aa27e961abe4f0d086d4af66a40435a0d4d9".equals(oracle.getString("sourceSha256")),"pinned external oracle hash");
        Method schema=AntigravityClient.class.getDeclaredMethod("plainSchema",JSONObject.class);schema.setAccessible(true);
        JSONArray rows=oracle.getJSONArray("schemas");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),input=row.getJSONObject("input");String before=input.toString();
            Object converted=schema.invoke(null,input);
            check(equal(converted,row.getJSONObject("expected")),"schema "+row.getString("name"));check(before.equals(input.toString()),"schema input unchanged "+row.getString("name"));
        }
        JSONArray thoughts=oracle.getJSONArray("thinking");
        for(int i=0;i<thoughts.length();i++){
            JSONObject row=thoughts.getJSONObject(i);String model=row.getString("model");
            if(!model.startsWith("gemini-3")&&!model.startsWith("gemini-4"))continue;
            JSONObject actual=AntigravityClient.buildEnvelope(payload(row.getString("effort")),model,"synthetic-project").getJSONObject("request");
            JSONObject gen=actual.optJSONObject("generationConfig");Object tc=gen==null?null:gen.opt("thinkingConfig");
            Object expected=row.getJSONObject("expected").getJSONObject("generationConfig").opt("thinkingConfig");
            check(equal(tc,expected),"thinking "+model+" requested="+row.getString("effort"));
            check(gen==null||!gen.has("maxOutputTokens"),"Gemini preserves upstream omission of maxOutputTokens");
        }
        JSONObject batch=oracle.getJSONObject("batch"),input=batch.getJSONObject("input"),expected=batch.getJSONObject("expected");
        JSONArray declarations=input.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations"),tools=new JSONArray();
        for(int i=0;i<declarations.length();i++){
            JSONObject d=declarations.getJSONObject(i);tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",d.getString("name")).put("description",d.getString("description")).put("parameters",d.getJSONObject("parametersJsonSchema"))));
        }
        JSONObject source=payload("high").put("tools",tools);source.getJSONArray("messages").put(0,new JSONObject().put("role","system").put("content","Use the declared tools only when needed."));source.getJSONArray("messages").put(new JSONObject().put("role","user").put("content","synthetic request"));String before=source.toString();
        JSONObject actual=AntigravityClient.buildEnvelope(source,batch.getString("model"),"synthetic-project").getJSONObject("request");
        JSONArray got=actual.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations"),want=expected.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        check(got.length()==103,"all 103 tools retained");
        for(int i=0;i<103;i++)check(equal(got.getJSONObject(i),want.getJSONObject(i)),"103-tool wire declaration "+i);
        check(equal(actual.opt("toolConfig"),expected.opt("toolConfig")),"Gemini tool selection config");
        check(equal(actual.opt("systemInstruction"),expected.opt("systemInstruction")),"ordinary system instruction preserved");
        check(before.equals(source.toString()),"entire caller payload unchanged");
        JSONObject required=AntigravityClient.buildEnvelope(payload("high").put("tools",tools).put("tool_choice","required"),"gemini-3.8-flash-high","synthetic-project");
        check("ANY".equals(required.getJSONObject("request").getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode")),"required choice retained");
        JSONObject none=AntigravityClient.buildEnvelope(payload("high").put("tools",tools).put("tool_choice","none"),"gemini-3.8-flash-high","synthetic-project");
        check(!none.getJSONObject("request").has("tools")&&!none.getJSONObject("request").has("toolConfig"),"explicit none still suppresses declarations");
        JSONObject named=AntigravityClient.buildEnvelope(payload("high").put("tools",tools).put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","synthetic_tool_7"))),"gemini-3.8-flash-high","synthetic-project");
        check("synthetic_tool_7".equals(named.getJSONObject("request").getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getJSONArray("allowedFunctionNames").getString(0)),"named tool choice retained");
        java.nio.file.Path dir=Files.createTempDirectory("antigravity58-wire-");
        AntigravityCompatibilityGatewayTest.Wire wire=new AntigravityCompatibilityGatewayTest.Wire("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"Synthetic rejection\"}}");wire.status=429;
        GatewayServer gateway=AntigravityCompatibilityGatewayTest.server(dir,wire);
        try {
            JSONObject cfg=ResponsesGatewayOfflineTest.config(gateway),m=cfg.getJSONArray("providers").getJSONObject(0).getJSONObject("modelMeta").getJSONObject("gemini-3.8-flash");
            m.put("defaultId","gemini-3.8-flash-high");
            JSONObject inputBody=new JSONObject(source.toString()).put("model","antigravity/gemini-3.8-flash").put("stream",true);
            fi.iki.elonen.NanoHTTPD.Response response=Audit53RegressionTest.request(gateway,"/v1/chat/completions","POST",inputBody.toString());
            check(response.getStatus().getRequestStatus()==429,"synthetic 103-tool request retains rejection status");Audit53RegressionTest.body(response);
            check(wire.opens==1,"103-tool rejection sends once");
            JSONObject sent=new JSONObject(wire.request.substring(wire.request.indexOf("\r\n\r\n")+4)).getJSONObject("request");
            check(equal(sent.opt("tools"),expected.opt("tools")),"actual gateway wire carries all normalized upstream declarations");
            check(equal(sent.opt("systemInstruction"),expected.opt("systemInstruction")),"actual wire retains ordinary system prompt");
            check(equal(sent.getJSONObject("generationConfig").opt("thinkingConfig"),expected.getJSONObject("generationConfig").opt("thinkingConfig")),"actual wire thinking config matches oracle");
        } finally {
            gateway.stop();try(java.util.stream.Stream<Path> files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception e){throw new RuntimeException(e);}});}
        }
        System.out.println("AntigravityRequestParityOfflineTest checks="+checks+" failures="+failures);
        if(failures>0)throw new AssertionError("upstream conversion mismatches="+failures);
    }
}
