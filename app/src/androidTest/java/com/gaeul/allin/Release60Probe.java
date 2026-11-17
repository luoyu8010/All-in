package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Loaded after the installed signed APK on app_process; synthetic data, no app files or accounts. */
public final class Release60Probe {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static UsageLedger.Row usage(JSONObject raw)throws Exception{
        UsageLedger.Row row=new UsageLedger.Row();row.requestId="release60-synthetic";row.status=200;
        Method method=GatewayServer.class.getDeclaredMethod("absorbUsage",UsageLedger.Row.class,JSONObject.class);method.setAccessible(true);method.invoke(null,row,raw);return row;
    }
    static JSONObject totals(UsageLedger.Row row){UsageLedger.Totals totals=new UsageLedger.Totals();totals.add(row);return totals.toJson();}
    public static void main(String[] args)throws Exception{
        for(String alias:new String[]{"prompt_cache_hit_tokens","cached_tokens","num_cached_tokens"}){
            JSONObject raw=new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10).put("prompt_tokens_details",new JSONObject().put("cached_tokens",0)).put(alias,800);
            UsageLedger.Row row=usage(raw);check(row.cacheRead==800,"signed APK reads documented alias "+alias);check(totals(row).getDouble("hitRate")==80,"signed APK alias denominator");
        }
        JSONObject nativeUsage=new JSONObject().put("input_tokens",200).put("output_tokens",10).put("cache_read_input_tokens",800).put("cache_creation_input_tokens",0);
        check(totals(usage(nativeUsage)).getDouble("hitRate")==80,"signed APK native denominator");
        check(totals(usage(new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10))).isNull("hitRate"),"signed APK absent cache stays unknown");
        JSONObject meta=new JSONObject().put("key","gmodel").put("display_name","GLM-5.3");
        JSONObject provider=new JSONObject().put("id","qoder_cn").put("protocol","qoder").put("models",new JSONArray().put("gmodel")).put("modelMeta",new JSONObject().put("gmodel",meta));
        QoderModelIdentity names=QoderModelIdentity.of(provider);check(names.publicId("gmodel").equals("GLM-5.3"),"signed APK public Qoder name");check(names.upstreamId("GLM-5.3").equals("gmodel"),"signed APK Qoder name reverses");
        JSONObject event=new JSONObject().put("response",new JSONObject().put("usageMetadata",new JSONObject().put("promptTokenCount",1000).put("cachedContentTokenCount",800).put("candidatesTokenCount",10)).put("candidates",new JSONArray().put(new JSONObject().put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","OK")))).put("finishReason","STOP"))));
        String wire="data: "+event+"\n\n";
        JSONObject completion=AntigravityClient.toOpenAiCompletion(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),"m");
        check(totals(usage(completion.getJSONObject("usage"))).getDouble("hitRate")==80,"signed APK Google nonstream cache");
        try(InputStream input=AntigravityClient.toOpenAiStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),"m");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);String stream=bytes.toString("UTF-8");boolean counted=false;
            for(String line:stream.split("\n"))if(line.startsWith("data: {")&&line.contains("usage")){check(totals(usage(new JSONObject(line.substring(6)).getJSONObject("usage"))).getDouble("hitRate")==80,"signed APK Google stream cache");counted=true;}
            check(counted&&stream.contains("[DONE]"),"signed APK stream completes");
        }
        System.out.println("RELEASE60_ART_PASS checks="+checks+" api="+android.os.Build.VERSION.SDK_INT);
    }
}
