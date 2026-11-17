package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;

/** Counterexamples for cache normalization, protocol conversions and immutable legacy records. */
public final class CacheAccountingRegressionTest {
    static int checks,failures;
    static void check(boolean ok,String name){checks++;if(!ok){failures++;System.out.println("FAIL "+name);}}
    static final Method absorb,parse,line;
    static {try{absorb=GatewayServer.class.getDeclaredMethod("absorbUsage",UsageLedger.Row.class,JSONObject.class);absorb.setAccessible(true);
        parse=UsageLedger.class.getDeclaredMethod("parse",String.class);parse.setAccessible(true);
        line=UsageLedger.class.getDeclaredMethod("toJsonLine",UsageLedger.Row.class);line.setAccessible(true);
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static UsageLedger.Row row(){UsageLedger.Row r=new UsageLedger.Row();r.requestId="synthetic-cache";r.status=200;return r;}
    static void apply(UsageLedger.Row r,JSONObject u)throws Exception{absorb.invoke(null,r,u);}
    static JSONObject totals(UsageLedger.Row row){UsageLedger.Totals t=new UsageLedger.Totals();t.add(row);return t.toJson();}
    static JSONObject usage(){return new JSONObject().put("prompt_tokens",1000).put("completion_tokens",10);}
    static void rate(UsageLedger.Row row,double expected,String name){JSONObject t=totals(row);check(!t.isNull("hitRate")&&Math.abs(t.optDouble("hitRate")-expected)<0.001,name);}
    static JSONObject bridge(String name,JSONObject u)throws Exception{Method m=ResponsesBridge.class.getDeclaredMethod(name,JSONObject.class);m.setAccessible(true);return (JSONObject)m.invoke(null,u);}
    static void parser()throws Exception{
        for(String provider:new String[]{"zcode","workbuddy","workbuddy-ai","raccoon","loomy","deepseek","siliconflow","openai","openrouter","tencent","cline","github-copilot","codex","qoder_cn","trae-cn","anthropic","minimax-code","antigravity","catpaw","deepseek_web"}){
            UsageLedger.Row r=row();r.prov=provider;apply(r,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800)));
            rate(r,80,"standard cache "+provider);check(r.cacheRead==800,"standard numerator "+provider);
        }
        for(String alias:new String[]{"prompt_cache_hit_tokens","cached_tokens","num_cached_tokens"}){
            for(boolean zero:new boolean[]{false,true}){UsageLedger.Row r=row();JSONObject u=usage().put(alias,800);
                if(zero)u.put("prompt_tokens_details",new JSONObject().put("cached_tokens",0));apply(r,u);
                check(r.cacheRead==800,"documented alias "+alias+" besideZero="+zero);rate(r,80,"alias hit rate "+alias+" besideZero="+zero);}
        }
        UsageLedger.Row inclusive=row();apply(inclusive,usage().put("cache_read_input_tokens",800));rate(inclusive,80,"prompt_tokens already includes cache");
        UsageLedger.Row relay=row();apply(relay,new JSONObject().put("prompt_tokens",200).put("completion_tokens",10).put("cache_read_input_tokens",800));rate(relay,80,"relay excludes cache when cache exceeds prompt per fixed Magpie");
        UsageLedger.Row aliases=row();apply(aliases,usage().put("prompt_cache_hit_tokens",800).put("cached_tokens",800).put("num_cached_tokens",800));check(aliases.cacheRead==800,"aliases never summed");
        UsageLedger.Row canonical=row();apply(canonical,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",600)).put("prompt_cache_hit_tokens",800));check(canonical.cacheRead==600,"positive canonical counter retains precedence");
        TraeProtocol.Decoder trae=new TraeProtocol.Decoder(null,"m");trae.accept("token_usage","{\"input_tokens\":200,\"output_tokens\":10,\"cache_read_input_tokens\":800}");
        UsageLedger.Row traeRow=row();apply(traeRow,trae.usage);rate(traeRow,80,"Trae preserves native denominator after Chat normalization");
        UsageLedger.Row nativeRow=row();JSONObject nativeUsage=new JSONObject().put("input_tokens",200).put("output_tokens",10).put("cache_read_input_tokens",600).put("cache_creation_input_tokens",200);
        apply(nativeRow,nativeUsage);rate(nativeRow,60,"native input excludes read and write");
        UsageLedger.Row normalized=row();apply(normalized,CacheUsage.toChat(nativeUsage));rate(normalized,60,"native normalized to Chat");
        UsageLedger.Row writeOnly=row();apply(writeOnly,usage().put("cache_creation_input_tokens",200));check(totals(writeOnly).isNull("hitRate"),"write without reported read is not a confirmed miss");
        UsageLedger.Row missing=row();apply(missing,usage());check(totals(missing).isNull("hitRate"),"absent cache remains unknown");
        UsageLedger.Row zero=row();apply(zero,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",0)));rate(zero,0,"explicit zero remains a real zero");
        for(Object invalid:new Object[]{JSONObject.NULL,-1,0.5,"800"}){UsageLedger.Row r=row();apply(r,usage().put("prompt_cache_hit_tokens",invalid));check(totals(r).isNull("hitRate"),"invalid alias is not known "+String.valueOf(invalid));}
        UsageLedger.Row unknown=row();apply(unknown,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800)).put("magpie_cache_unknown",true));check(totals(unknown).isNull("hitRate"),"explicit unknown policy wins");
        UsageLedger.Row split=row();apply(split,new JSONObject().put("cache_read_input_tokens",600).put("cache_creation_input_tokens",200));apply(split,new JSONObject().put("input_tokens",200).put("output_tokens",10));rate(split,60,"native convention survives split usage frames");
        UsageLedger.Row two=row();two.beginCompatUsageSend();apply(two,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800).put("cache_write_tokens",0)));two.beginCompatUsageSend();apply(two,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",400).put("cache_write_tokens",0)));rate(two,60,"two physical sends aggregate once");check(two.cacheRead==1200&&two.in==2000,"no duplicate compat counters");
        UsageLedger.Row mixed=row();mixed.beginCompatUsageSend();apply(mixed,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800).put("cache_write_tokens",0)));mixed.beginCompatUsageSend();apply(mixed,new JSONObject().put("input_tokens",200).put("output_tokens",10).put("cache_read_input_tokens",800).put("cache_creation_input_tokens",0));rate(mixed,80,"mixed physical-send input conventions remain isolated");
        UsageLedger.Row reverse=row();reverse.beginCompatUsageSend();apply(reverse,new JSONObject().put("input_tokens",200).put("output_tokens",10).put("cache_read_input_tokens",800).put("cache_creation_input_tokens",0));reverse.beginCompatUsageSend();apply(reverse,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800).put("cache_write_tokens",0)));rate(reverse,80,"reverse mixed physical-send conventions");
        UsageLedger.Row partial=row();partial.beginCompatUsageSend();apply(partial,usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",800).put("cache_write_tokens",0)));partial.beginCompatUsageSend();apply(partial,usage().put("magpie_cache_unknown",true));check(totals(partial).isNull("hitRate"),"unknown compatibility send keeps aggregate partial");
    }
    static void bridges()throws Exception{
        for(String field:new String[]{"prompt_cache_hit_tokens","cached_tokens","num_cached_tokens","cache_read_input_tokens"}){
            JSONObject converted=bridge("responsesUsage",usage().put(field,800));UsageLedger.Row r=row();apply(r,converted);rate(r,80,"Responses conversion preserves "+field);
            JSONObject completion=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","OK")).put("finish_reason","stop"))).put("usage",usage().put(field,800));
            JSONObject nativeResult=AnthropicBridge.responseToAnthropic(completion,"m");check(nativeResult.getJSONObject("usage").optLong("cache_read_input_tokens")==800,"Messages conversion preserves "+field);
        }
        JSONObject unknown=usage().put("prompt_tokens_details",new JSONObject().put("cached_tokens",0)).put("magpie_cache_unknown",true);
        JSONObject responses=bridge("responsesUsage",unknown),back=bridge("chatUsage",responses);
        check(back.optBoolean("magpie_cache_unknown"),"Responses roundtrip retains unknown cache policy");
        JSONObject message=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","OK")).put("finish_reason","stop"))).put("usage",unknown);
        check(AnthropicBridge.responseToAnthropic(message,"m").getJSONObject("usage").optBoolean("magpie_cache_unknown"),"Messages bridge retains unknown cache policy");
        JSONObject accumulator=new JSONObject();GatewayServer.mergeReportedUsage(accumulator,usage().put("prompt_cache_hit_tokens",800).put("magpie_cache_unknown",true));
        check(accumulator.optInt("prompt_cache_hit_tokens")==800&&accumulator.optBoolean("magpie_cache_unknown"),"aggregation preserves aliases and unknown marker");
    }
    static String google(Integer cached,boolean includeUsage){
        JSONObject response=new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","OK")))).put("finishReason","STOP")));
        if(includeUsage){JSONObject u=new JSONObject().put("promptTokenCount",1000).put("candidatesTokenCount",10).put("thoughtsTokenCount",5);if(cached!=null)u.put("cachedContentTokenCount",cached);response.put("usageMetadata",u);}
        return "data: "+new JSONObject().put("response",response)+"\n\n";
    }
    static void antigravity()throws Exception{
        for(Integer cached:new Integer[]{800,0,null})for(boolean stream:new boolean[]{false,true}){
            String wire=google(cached,true);JSONObject u;
            if(stream){try(InputStream in=AntigravityClient.toOpenAiStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),"m")){
                String output=new String(in.readAllBytes(),StandardCharsets.UTF_8);u=new JSONObject();
                for(String s:output.split("\n"))if(s.startsWith("data: {")&&s.contains("usage"))u=new JSONObject(s.substring(6)).getJSONObject("usage");
                check(output.contains("[DONE]"),"Google stream still terminates");}}
            else u=AntigravityClient.toOpenAiCompletion(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),"m").getJSONObject("usage");
            UsageLedger.Row r=row();apply(r,u);check(r.in==1000&&r.out==15,"Google input includes cache and preserves thoughts stream="+stream);
            if(cached==null)check(totals(r).isNull("hitRate"),"Google absent cache stays unknown");else rate(r,cached/10.0,"Google cache preserved stream="+stream+" cached="+cached);
        }
        JSONObject no=AntigravityClient.toOpenAiCompletion(new ByteArrayInputStream(google(null,false).getBytes(StandardCharsets.UTF_8)),"m");check(!no.has("usage")||no.getJSONObject("usage").length()==0,"Google absent usage not fabricated");
    }
    static void lateGoogleUsage()throws Exception{
        String finished=google(null,false);
        String tail="data: "+new JSONObject().put("response",new JSONObject().put("usageMetadata",new JSONObject().put("promptTokenCount",1000).put("cachedContentTokenCount",800).put("candidatesTokenCount",10)))+"\n\n";
        try(InputStream in=AntigravityClient.toOpenAiStream(new ByteArrayInputStream((finished+tail).getBytes(StandardCharsets.UTF_8)),"m")) {
            String output=new String(in.readAllBytes(),StandardCharsets.UTF_8);UsageLedger.Row r=row();int finish=0,usage=0;
            for(String text:output.split("\n"))if(text.startsWith("data: {")){
                JSONObject chunk=new JSONObject(text.substring(6));if(chunk.optJSONObject("usage")!=null){apply(r,chunk.getJSONObject("usage"));usage++;}
                JSONArray choices=chunk.optJSONArray("choices");if(choices!=null&&choices.length()>0&&!choices.getJSONObject(0).isNull("finish_reason"))finish++;
            }
            rate(r,80,"Google usage arriving after finishReason survives");check(finish==1&&usage==1,"late Google usage does not duplicate semantic finish");
        }
        try(InputStream in=AntigravityClient.toOpenAiStream(new ByteArrayInputStream(finished.getBytes(StandardCharsets.UTF_8)),"m")){
            String output=new String(in.readAllBytes(),StandardCharsets.UTF_8);check(!output.contains("\"usage\""),"Google absent usage remains absent in SSE");}
    }
    static void legacy()throws Exception{
        for(String raw:new String[]{"{\"in\":1000,\"out\":10,\"st\":200}","{\"in\":1000,\"out\":10,\"cw\":200,\"st\":200}"}){
            UsageLedger.Row r=(UsageLedger.Row)parse.invoke(null,raw);check(r!=null&&totals(r).isNull("hitRate"),"legacy missing read not fabricated zero");
            UsageLedger.Row reloaded=(UsageLedger.Row)parse.invoke(null,(String)line.invoke(null,r));check(totals(reloaded).isNull("hitRate"),"legacy unknown survives serialization");
        }
        UsageLedger.Row explicit=(UsageLedger.Row)parse.invoke(null,"{\"in\":1000,\"out\":10,\"cr\":0,\"cw\":0,\"st\":200}");rate(explicit,0,"explicit legacy zero preserved");
        UsageLedger.Row tiny=row();apply(tiny,new JSONObject().put("prompt_tokens",10000).put("completion_tokens",10).put("prompt_tokens_details",new JSONObject().put("cached_tokens",1)));check(totals(tiny).optDouble("hitRateExact",0)>0,"small positive rate available for truthful display");
    }
    public static void main(String[] args)throws Exception{parser();bridges();antigravity();lateGoogleUsage();legacy();System.out.println("CacheAccountingRegressionTest checks="+checks+" failures="+failures);if(failures>0)throw new AssertionError("cache regression failures="+failures);}
}
