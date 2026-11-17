package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

/** Documented cache aliases and input conventions; absent counters stay absent. */
final class CacheUsage {
    private CacheUsage() {}
    static Long count(JSONObject object,String key) {
        Object value=object==null?null:object.opt(key);
        if(!(value instanceof Number))return null;
        double n=((Number)value).doubleValue();
        if(!Double.isFinite(n)||n<0||n!=Math.rint(n)||n>9007199254740991d)return null;
        return ((Number)value).longValue();
    }
    private static Long first(JSONObject o,String... keys) {
        for(String key:keys){Long n=count(o,key);if(n!=null)return n;}return null;
    }
    // A populated canonical counter wins; an explicit zero permits a documented alias fallback.
    private static Long prefer(Long... values) {
        Long zero=null;for(Long n:values)if(n!=null){if(n>0)return n;zero=n;}return zero;
    }
    static Long read(JSONObject u) {
        if(u==null)return null;
        return prefer(count(u.optJSONObject("prompt_tokens_details"),"cached_tokens"),
            count(u.optJSONObject("input_tokens_details"),"cached_tokens"),count(u,"prompt_cache_hit_tokens"),
            count(u,"cached_tokens"),count(u,"num_cached_tokens"),count(u,"cache_read_input_tokens"),
            count(u,"cacheReadInputTokens"),count(u,"cachedContentTokenCount"));
    }
    static Long write(JSONObject u) {
        if(u==null)return null;
        return prefer(count(u.optJSONObject("prompt_tokens_details"),"cache_write_tokens"),
            count(u.optJSONObject("input_tokens_details"),"cache_write_tokens"),count(u,"cache_creation_input_tokens"),count(u,"cacheCreationInputTokens"));
    }
    /** Null leaves a prior partial frame's convention alone. OpenAI/Responses count cached input. */
    static Boolean excludesCache(JSONObject u) {
        if(u==null)return null;
        Long prompt=first(u,"prompt_tokens","promptTokens","promptTokenCount");
        if(prompt!=null) {
            JSONObject detail=u.optJSONObject("prompt_tokens_details");
            boolean standard=count(detail,"cached_tokens")!=null||count(detail,"cache_write_tokens")!=null
                ||first(u,"prompt_cache_hit_tokens","cached_tokens","num_cached_tokens","cachedContentTokenCount")!=null;
            Long read=first(u,"cache_read_input_tokens","cacheReadInputTokens"),write=first(u,"cache_creation_input_tokens","cacheCreationInputTokens");
            // The pinned Magpie cUsage treats a relay's prompt below its native cache counters
            // as Anthropic-style input. A canonical Chat count or detail stays inclusive.
            if(!standard && (read!=null||write!=null))return prompt<(read==null?0:read)+(write==null?0:write);
            return false;
        }
        JSONObject detail=u.optJSONObject("input_tokens_details");
        if(count(detail,"cached_tokens")!=null||count(detail,"cache_write_tokens")!=null)return false;
        if(first(u,"cache_read_input_tokens","cacheReadInputTokens","cache_creation_input_tokens","cacheCreationInputTokens")!=null)return true;
        if(count(u,"cachedContentTokenCount")!=null)return false;
        return null;
    }
    static JSONObject toChat(JSONObject usage) {
        JSONObject out=usage==null?new JSONObject():new JSONObject(usage.toString());
        if(usage==null)return out;
        Long input=first(usage,"prompt_tokens","input_tokens","promptTokenCount","promptTokens","inputTokens");
        Long output=first(usage,"completion_tokens","output_tokens","candidatesTokenCount","completionTokens","outputTokens");
        Long read=read(usage),write=write(usage);
        if(input!=null){long prompt=input;if(Boolean.TRUE.equals(excludesCache(usage)))prompt+= (read==null?0:read)+(write==null?0:write);out.put("prompt_tokens",prompt);}
        if(output!=null)out.put("completion_tokens",output);
        if(read!=null||write!=null){JSONObject old=out.optJSONObject("prompt_tokens_details");JSONObject details=old==null?new JSONObject():new JSONObject(old.toString());
            if(read!=null)details.put("cached_tokens",read);if(write!=null)details.put("cache_write_tokens",write);out.put("prompt_tokens_details",details);}
        if(input!=null&&output!=null)out.put("total_tokens",out.optLong("prompt_tokens")+output);
        return out;
    }
}
