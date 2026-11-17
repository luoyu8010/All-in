package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Public Qoder names from the saved official catalog; upstream keys remain untouched. */
final class QoderModelIdentity {
    private final Map<String,String> names = new LinkedHashMap<>();
    private final Map<String,String> ids = new LinkedHashMap<>();

    static QoderModelIdentity of(JSONObject provider) { return new QoderModelIdentity(provider); }

    /** Canonical reference for existing allow lists and group targets; does not select an account. */
    static String upstreamRef(JSONObject config,String ref) {
        int slash=ref==null?-1:ref.indexOf('/');
        if (slash<=0 || config==null) return ref;
        String providerId=ref.substring(0,slash);
        JSONArray providers=config.optJSONArray("providers");
        for (int i=0;providers!=null && i<providers.length();i++) {
            JSONObject provider=providers.optJSONObject(i);
            if (provider!=null && providerId.equals(provider.optString("id")))
                return providerId+"/"+of(provider).upstreamId(ref.substring(slash+1));
        }
        return ref;
    }

    private QoderModelIdentity(JSONObject provider) {
        if (provider == null || !"qoder".equals(provider.optString("protocol"))) return;
        JSONArray models = provider.optJSONArray("models");
        for (int i=0; models!=null && i<models.length(); i++) {
            String key=models.optString(i, "");
            if (!key.isEmpty()) names.put(key, label(metadata(provider,key),key));
        }
        Map<String,Integer> counts=new HashMap<>();
        for (String name:names.values()) counts.put(name,counts.containsKey(name)?counts.get(name)+1:1);
        Set<String> raw=names.keySet();
        Map<String,Integer> idCounts=new HashMap<>();
        for (Map.Entry<String,String> entry:names.entrySet()) {
            String key=entry.getKey(), name=entry.getValue(), id=name;
            if (!name.equals(key) && (counts.get(name)>1 || raw.contains(name))) {
                id=name+" ("+key+")";
                // Reserve all raw keys and all original labels, including ones visited later.
                if (raw.contains(id) || counts.containsKey(id)) id=key;
            }
            ids.put(key,id);
            idCounts.put(id,idCounts.containsKey(id)?idCounts.get(id)+1:1);
        }
        // Pathological labels can also collide after disambiguation; never merge routes.
        for (Map.Entry<String,String> entry:ids.entrySet())
            if (idCounts.get(entry.getValue())>1) entry.setValue(entry.getKey());
    }

    private static JSONObject metadata(JSONObject provider,String key) {
        JSONObject all=provider.optJSONObject("modelMeta"), meta=all==null?null:all.optJSONObject(key);
        if (meta!=null) return meta;
        JSONArray accounts=provider.optJSONArray("accounts");
        for (int i=0; accounts!=null && i<accounts.length(); i++) {
            JSONObject account=accounts.optJSONObject(i);
            if (account==null || !account.optBoolean("enabled",true) || account.optBoolean("off",false)
                    || "off".equalsIgnoreCase(account.optString("status"))) continue;
            all=account.optJSONObject("modelMeta"); meta=all==null?null:all.optJSONObject(key);
            if (meta!=null && key.equals(meta.optString("key"))) return meta;
        }
        return null;
    }

    private static String label(JSONObject meta,String fallback) {
        // Same field priority as dashboard.modelDisplayName; no fixed slot-to-family table.
        for (String field:new String[]{"display_name","displayName","name"}) {
            Object value=meta==null?null:meta.opt(field);
            if (!(value instanceof String)) continue;
            String text=((String)value).trim();
            if (text.isEmpty() || text.length()>256) continue;
            boolean valid=true;
            for (int i=0;i<text.length();i++) if (Character.isISOControl(text.charAt(i))) { valid=false; break; }
            if (valid) return text;
        }
        return fallback;
    }

    String publicId(String raw) { String id=ids.get(raw); return id==null?raw:id; }
    String name(String raw) { String name=names.get(raw); return name==null?raw:name; }
    String upstreamId(String requested) {
        // A pre-existing raw route always wins, even when another model's label resembles it.
        if (names.containsKey(requested)) return requested;
        for (Map.Entry<String,String> entry:ids.entrySet())
            if (entry.getValue().equals(requested)) return entry.getKey();
        return requested;
    }
}
