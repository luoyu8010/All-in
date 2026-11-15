package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory, account-scoped generations. Caller holds its configuration monitor. */
final class AccountGenerations {
    private static final class Entry {
        final JSONObject state; final long revision, incarnation;
        Entry(JSONObject state,long revision,long incarnation){this.state=state;this.revision=revision;this.incarnation=incarnation;}
    }
    private Map<String,Entry> entries=new HashMap<>();
    private long next;
    static String identity(JSONObject a) {
        if(a!=null)for(String k:new String[]{"id","accountId","uid"}){
            String value=a.optString(k,"").trim();if(!value.isEmpty())return k+":"+value;
        }
        return null;
    }
    static boolean enabled(JSONObject a) {
        return a!=null&&a.optBoolean("enabled",true)&&!a.optBoolean("off",false)&&!"off".equalsIgnoreCase(a.optString("status",""));
    }
    /** Display/catalog bookkeeping is not a new credential or account incarnation. */
    static JSONObject state(JSONObject account) {
        JSONObject state=new JSONObject(account.toString());
        for(String field:new String[]{"name","models","modelMeta","catalogUpdatedAt","catalogRenewedAt","checkinDate","checkinOutcome","checkinCredit"})state.remove(field);
        return state;
    }
    static boolean sameState(JSONObject a,JSONObject b){return a!=null&&b!=null&&state(a).similar(state(b));}
    private static JSONObject incarnationState(JSONObject a) {
        JSONObject out=state(a);
        for(String k:new String[]{"access","token","refresh","expires","idToken","expiresAt","lastRefresh","deviceToken","deviceRefresh"})out.remove(k);
        return out;
    }
    private static String key(String provider,String id){return provider.length()+":"+provider+":"+id;}
    void observe(JSONObject config) {
        Map<String,JSONObject> states=new HashMap<>();Set<String> duplicateProviders=new HashSet<>(),seenProviders=new HashSet<>();
        JSONArray providers=config==null?null:config.optJSONArray("providers");
        for(int i=0;providers!=null&&i<providers.length();i++){JSONObject p=providers.optJSONObject(i);if(p!=null&&!seenProviders.add(p.optString("id")))duplicateProviders.add(p.optString("id"));}
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(!enabled(p)||duplicateProviders.contains(p.optString("id")))continue;
            String provider=p.optString("id");JSONArray accounts=p.optJSONArray("accounts");Set<String> duplicates=new HashSet<>(),seen=new HashSet<>();
            for(int j=0;accounts!=null&&j<accounts.length();j++) {JSONObject a=accounts.optJSONObject(j);String id=identity(a);if(id==null)continue;String k=key(provider,id);if(!seen.add(k)){duplicates.add(k);continue;}if(enabled(a))states.put(k,state(a));}
            for(String k:duplicates)states.remove(k);
        }
        Map<String,Entry> updated=new HashMap<>();
        for(Map.Entry<String,JSONObject> item:states.entrySet()) {
            Entry old=entries.get(item.getKey());
            long incarnation=old!=null&&incarnationState(old.state).similar(incarnationState(item.getValue()))?old.incarnation:++next;
            updated.put(item.getKey(),old!=null&&old.state.similar(item.getValue())?old:new Entry(item.getValue(),++next,incarnation));
        }
        // Deleted/disabled accounts disappear. Re-adding identical bytes gets a NEW revision,
        // unlike copying an unchanged account during an unrelated configuration transaction.
        entries=updated;
    }
    long revision(String provider,String id){Entry e=entries.get(key(provider,id));return e==null?0:e.revision;}
    long incarnation(String provider,String id){Entry e=entries.get(key(provider,id));return e==null?0:e.incarnation;}
}
