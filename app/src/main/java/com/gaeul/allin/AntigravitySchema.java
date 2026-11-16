package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.util.*;

/** OpenAPI-style schema subset ported from pinned community 0.2.1 plainSchema. MIT notices bundled. */
final class AntigravitySchema {
    private static final Set<String> KEEP=new HashSet<>(Arrays.asList("description","nullable","required","pattern","minimum","maximum","minLength","maxLength","minItems","maxItems","minProperties","maxProperties","propertyOrdering"));
    private final JSONObject root,defs=new JSONObject();
    private final Set<String> expanding=new HashSet<>();
    private AntigravitySchema(JSONObject root){this.root=root;for(String name:new String[]{"$defs","definitions"}){JSONObject d=root.optJSONObject(name);if(d!=null)for(String k:d.keySet())defs.put(k,d.opt(k));}}
    static JSONObject convert(JSONObject schema){Object result=new AntigravitySchema(schema).walk(schema,0,"");return result instanceof JSONObject?(JSONObject)result:new JSONObject();}
    private Object reference(String ref){
        if("#".equals(ref))return root;
        String last=ref.substring(ref.lastIndexOf('/')+1);
        if(ref.startsWith("#/")){
            Object current=root;
            for(String raw:ref.substring(2).split("/",-1)){
                String token=raw.replace("~1","/").replace("~0","~");
                if(current instanceof JSONObject)current=((JSONObject)current).opt(token);
                else if(current instanceof JSONArray){try{if(!token.matches("[0-9]+"))return null;current=((JSONArray)current).opt(Integer.parseInt(token));}catch(NumberFormatException e){return null;}}
                else return null;
                if(current==null||current==JSONObject.NULL)return defs.opt(last);
            }
            return current;
        }
        return defs.opt(last);
    }
    private JSONObject resolve(Object value){if(!(value instanceof JSONObject))return null;JSONObject m=(JSONObject)value;Object ref=m.opt("$ref");if(ref instanceof String){Object resolved=reference((String)ref);return resolved instanceof JSONObject?(JSONObject)resolved:null;}return m;}
    private static List<String> required(Object value){List<String> names=new ArrayList<>();if(value instanceof JSONArray)for(Object e:(JSONArray)value)if(e instanceof String)names.add((String)e);return names;}
    private static boolean ownShape(JSONObject x){
        if(present(x,"properties")||present(x,"items"))return true;
        Object type=x.opt("type");if(type instanceof String)return !"null".equals(type);
        if(type instanceof JSONArray)for(Object t:(JSONArray)type)if(!"null".equals(t))return true;
        return false;
    }
    private static boolean present(JSONObject x,String k){return x.has(k)&&x.opt(k)!=JSONObject.NULL;}
    private static JSONObject copy(JSONObject x){return new JSONObject(x.toString());}
    private JSONObject foldUnion(JSONObject x){
        JSONObject out=copy(x);out.remove("anyOf");out.remove("oneOf");
        boolean object="object".equals(x.opt("type"))||present(x,"properties");
        JSONArray types=x.optJSONArray("type");if(types!=null)for(Object t:types)if("object".equals(t))object=true;
        JSONObject props=x.optJSONObject("properties")==null?new JSONObject():copy(x.getJSONObject("properties"));
        List<String> common=new ArrayList<>();int branches=0;
        for(String k:new String[]{"anyOf","oneOf"}){
            JSONArray members=x.optJSONArray(k);if(members==null)continue;
            for(Object a:members){JSONObject m=resolve(a);if(m==null)continue;if("null".equals(m.opt("type"))){out.put("nullable",true);continue;}if(!object)continue;
                JSONObject bp=m.optJSONObject("properties");if(bp!=null){List<String> keys=new ArrayList<>(bp.keySet());Collections.sort(keys);for(String pk:keys)if(!props.has(pk))props.put(pk,bp.opt(pk));}
                List<String> br=required(m.opt("required"));if(branches++==0)common.addAll(br);else common.retainAll(br);
            }
        }
        if(object){if(props.length()>0)out.put("properties",props);LinkedHashSet<String> req=new LinkedHashSet<>(required(x.opt("required")));req.addAll(common);if(!req.isEmpty())out.put("required",new JSONArray(req));}
        return out;
    }
    private static String typeOf(JSONObject m){Object t=m.opt("type");return t instanceof String?(String)t:present(m,"properties")?"object":present(m,"items")?"array":"object";}
    private static boolean equal(Object a,Object b){return a==b||a instanceof JSONObject&&b instanceof JSONObject&&((JSONObject)a).similar(b)||a instanceof JSONArray&&b instanceof JSONArray&&((JSONArray)a).similar(b)||a!=null&&a.equals(b);}
    private static JSONObject oneSays(JSONArray members){JSONObject one=null;for(Object m:members){if(!(m instanceof JSONObject)||((JSONObject)m).length()==0)continue;if(one!=null&&!equal(one,m))return null;one=(JSONObject)m;}return one;}
    private Object walk(Object value,int depth,String path){
        if(value instanceof Boolean)return Boolean.TRUE.equals(value)?new JSONObject():null;
        if(value instanceof JSONArray){JSONArray out=new JSONArray();for(Object e:(JSONArray)value){Object w=walk(e,depth,path);out.put(w==null?JSONObject.NULL:w);}return out;}
        if(!(value instanceof JSONObject))return value;
        JSONObject x=(JSONObject)value;
        if(depth>32)return new JSONObject().put("type","object");
        Object rawRef=x.opt("$ref");
        if(rawRef instanceof String){String ref=(String)rawRef;Object d=reference(ref);if(d==null||d==JSONObject.NULL)return new JSONObject().put("type","object");String r=ref.startsWith("#")?ref.substring(1):null;
            boolean self=r!=null&&(r.isEmpty()||path.equals(r)||path.startsWith(r+"/"));
            if(expanding.contains(ref)||self)return new JSONObject().put("type",d instanceof JSONObject?typeOf((JSONObject)d):"object");
            expanding.add(ref);try{return walk(d,depth+1,r==null?path:r);}finally{expanding.remove(ref);}
        }
        JSONArray all=x.optJSONArray("allOf");
        if(all!=null){JSONObject merged=copy(x);merged.remove("allOf");List<String> req=required(x.opt("required"));
            for(Object e:all){JSONObject resolved=resolve(e);if(resolved!=null)req.addAll(required(resolved.opt("required")));Object w=walk(e,depth+1,path+"/allOf");if(!(w instanceof JSONObject))continue;JSONObject m=(JSONObject)w;
                for(String k:m.keySet()){if("required".equals(k))continue;if("properties".equals(k)&&m.optJSONObject(k)!=null){JSONObject p=merged.optJSONObject(k)==null?new JSONObject():copy(merged.getJSONObject(k));for(String pk:m.getJSONObject(k).keySet())p.put(pk,m.getJSONObject(k).opt(pk));merged.put(k,p);}else if(!merged.has(k))merged.put(k,m.opt(k));}
            }
            if(!req.isEmpty())merged.put("required",new JSONArray(new LinkedHashSet<>(req)));return walk(merged,depth+1,path);
        }
        if((present(x,"anyOf")||present(x,"oneOf"))&&ownShape(x))return walk(foldUnion(x),depth+1,path);
        for(String k:new String[]{"anyOf","oneOf"}){JSONArray union=x.optJSONArray(k);if(union==null)continue;JSONObject pick=null;boolean nullable=false;
            for(Object a:union)if(a instanceof JSONObject){JSONObject m=(JSONObject)a;if("null".equals(m.opt("type")))nullable=true;else if(pick==null)pick=m;}
            JSONObject out=new JSONObject();if(pick!=null){Object w=walk(pick,depth+1,path+"/"+k);if(w instanceof JSONObject)out=(JSONObject)w;}
            if(x.opt("description") instanceof String&&!present(out,"description"))out.put("description",x.opt("description"));if(nullable)out.put("nullable",true);if(!present(out,"type"))out.put("type","string");return out;
        }
        JSONObject out=new JSONObject();
        for(String k:x.keySet()){
            Object val=x.opt(k);
            if(KEEP.contains(k))out.put(k,val);
            else if("exclusiveMinimum".equals(k)||"exclusiveMaximum".equals(k)){String b="exclusiveMinimum".equals(k)?"minimum":"maximum";if(val instanceof Number&&!present(x,b))out.put(b,val);}
            else if("type".equals(k)){if(val instanceof JSONArray){for(Object t:(JSONArray)val)if("null".equals(t))out.put("nullable",true);else if(!out.has("type"))out.put("type",t);
                    for(Object t:(JSONArray)val)if("array".equals(t)&&present(x,"items")||"object".equals(t)&&present(x,"properties")){out.put("type",t);break;}}
                else out.put("type",val);
            }else if("properties".equals(k)){JSONObject props=new JSONObject();if(val instanceof JSONObject)for(String pk:((JSONObject)val).keySet()){String escaped=pk.replace("~","~0").replace("/","~1");Object w=walk(((JSONObject)val).opt(pk),depth+1,path+"/properties/"+escaped);if(w!=null&&w!=JSONObject.NULL)props.put(pk,w);}out.put(k,props);}
            else if("items".equals(k)){Object w=walk(val,depth+1,path+"/items");if(w!=null&&w!=JSONObject.NULL)out.put(k,w);else if(!present(x,"maxItems")){out.put("items",new JSONObject()).put("maxItems",0);}}
            else if("enum".equals(k)&&val instanceof JSONArray){JSONArray list=(JSONArray)val;boolean strings=true;for(Object e:list)if(!(e instanceof String))strings=false;
                if(strings){JSONArray values=new JSONArray();for(Object e:list)if(!((String)e).trim().isEmpty())values.put(e);if(values.length()>0)out.put("enum",values);}}
        }
        Object constant=x.opt("const");if(constant instanceof String&&!((String)constant).trim().isEmpty())out.put("enum",new JSONArray().put(constant));
        if(!present(out,"type")){if(present(out,"properties"))out.put("type","object");else if(present(out,"items"))out.put("type","array");}
        if("array".equals(out.opt("type"))){if(out.opt("items") instanceof JSONArray){JSONObject one=oneSays(out.getJSONArray("items"));out.put("items",one==null?JSONObject.NULL:one);}
            if(!present(out,"items")){JSONArray prefix=x.optJSONArray("prefixItems");if(prefix!=null&&prefix.length()>0){JSONObject one=oneSays(prefix);if(one!=null)out.put("items",walk(one,depth+1,path+"/items"));}if(!present(out,"items"))out.put("items",new JSONObject());}}
        if(out.opt("type") instanceof String){if(!"array".equals(out.opt("type")))for(String k:new String[]{"items","minItems","maxItems"})out.remove(k);
            if(!"object".equals(out.opt("type")))for(String k:new String[]{"properties","required","minProperties","maxProperties","propertyOrdering"})out.remove(k);}
        JSONArray required=out.optJSONArray("required");if(required!=null){JSONObject props=out.optJSONObject("properties");JSONArray keep=new JSONArray();for(Object n:required)if(n instanceof String&&props!=null&&present(props,(String)n))keep.put(n);if(keep.length()>0)out.put("required",keep);else out.remove("required");}
        return out;
    }
}
