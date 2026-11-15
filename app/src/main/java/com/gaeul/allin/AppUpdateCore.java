package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Public release metadata only. Does not read, write or export gateway/account configuration. */
final class AppUpdateCore {
    static final String API="https://api.github.com/repos/xunianooo/All-in/releases/latest";
    static final String DOWNLOAD="https://github.com/xunianooo/All-in/releases/latest";
    static final long INTERVAL=9*60*60*1000L, ERROR_BACKOFF=15*60*1000L, MAX_BACKOFF=24*60*60*1000L;
    interface Store {JSONObject read()throws Exception;void write(JSONObject state)throws Exception;}
    interface Clock {long now();}
    interface Transport {Reply get(String url,int limit)throws Exception;}
    static final class Reply {
        final int status;final String body;final long retryMillis;
        Reply(int status,String body,long retryMillis){this.status=status;this.body=body;this.retryMillis=retryMillis;}
    }
    static final class Failure extends IOException {
        final String code;final long delay;
        Failure(String code,long delay){super(code);this.code=code;this.delay=delay;}
    }
    final Store store;final Transport transport;final Clock clock;final int currentCode;final String currentName;
    private boolean busy;
    private final java.util.concurrent.atomic.AtomicLong eventSequence=new java.util.concurrent.atomic.AtomicLong();
    AppUpdateCore(Store store,Transport transport,Clock clock,int currentCode,String currentName){this.store=store;this.transport=transport;this.clock=clock;this.currentCode=currentCode;this.currentName=currentName;}
    static JSONObject object(String text)throws IOException {
        try{JSONTokener t=new JSONTokener(text);Object v=t.nextValue();if(!(v instanceof JSONObject)||t.nextClean()!=0)throw new IOException("invalid metadata");return (JSONObject)v;}catch(Exception e){throw new IOException("invalid metadata");}
    }
    static String normalized(String name)throws IOException {
        if(name==null||!name.matches("v?[0-9]{1,16}\\.[0-9]{1,16}\\.[0-9]{1,16}(?:-test)?"))throw new IOException("invalid release version");return name.replaceFirst("^v","").replaceFirst("-test$","");
    }
    static int compareVersions(String a,String b)throws IOException {
        String[] x=normalized(a).split("\\."),y=normalized(b).split("\\.");for(int i=0;i<3;i++){int c=new BigInteger(x[i]).compareTo(new BigInteger(y[i]));if(c!=0)return c;}return 0;
    }
    static int integer(Object n)throws IOException {if(!(n instanceof Number))throw new IOException("invalid versionCode");double d=((Number)n).doubleValue();long l=((Number)n).longValue();if(l<=0||l>Integer.MAX_VALUE||!Double.isFinite(d)||d!=(double)l)throw new IOException("invalid versionCode");return (int)l;}
    static String summary(String body){if(body==null)return "";String s=body.replaceAll("(?s)<[^>]*>","").replaceAll("!\\[[^]]*\\]\\([^)]*\\)","").replaceAll("\\[([^]]+)\\]\\([^)]*\\)","$1").replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]","");StringBuilder b=new StringBuilder();int lines=0;for(String line:s.split("\\r?\\n")){line=line.replaceFirst("^[#*>\\s-]+","").trim();if(line.isEmpty()||line.startsWith("```"))continue;if(lines++==3)break;if(b.length()>0)b.append('\n');b.append(line);if(b.length()>320)break;}return b.length()>320?b.substring(0,320):b.toString();}
    private JSONObject fetch()throws Exception {
        Reply r=transport.get(API,1024*1024);if(r.status!=200)throw new Failure(r.status==403||r.status==429?"rate_limited":"github_unavailable",r.retryMillis);
        JSONObject release=object(r.body);if(!Boolean.FALSE.equals(release.opt("draft"))||!Boolean.FALSE.equals(release.opt("prerelease")))throw new Failure("invalid_release",ERROR_BACKOFF);
        String tag=release.optString("tag_name","");normalized(tag);String releaseUrl="https://github.com/xunianooo/All-in/releases/tag/"+tag;
        if(!releaseUrl.equals(release.optString("html_url","")))throw new Failure("invalid_release",ERROR_BACKOFF);
        JSONObject latest=new JSONObject().put("versionName",tag.replaceFirst("^v","")).put("tag",tag).put("summary",summary(release.optString("body",""))).put("releaseUrl",releaseUrl).put("versionCode",0);
        JSONArray assets=release.optJSONArray("assets");int manifests=0;
        for(int i=0;assets!=null&&i<assets.length();i++){
            JSONObject asset=assets.optJSONObject(i);if(asset==null||!"update.json".equals(asset.optString("name")))continue;if(++manifests>1)throw new Failure("invalid_manifest",ERROR_BACKOFF);
            String expected="https://github.com/xunianooo/All-in/releases/download/"+tag+"/update.json";
            if(!expected.equals(asset.optString("browser_download_url")))throw new Failure("invalid_manifest",ERROR_BACKOFF);
            Reply m=transport.get(expected,65536);if(m.status!=200)throw new Failure(m.status==403||m.status==429?"rate_limited":"manifest_unavailable",m.retryMillis);
            JSONObject info=object(m.body);if(integer(info.opt("schema"))!=1||!tag.equals(info.optString("tag"))||!normalized(tag).equals(normalized(info.optString("versionName"))))throw new Failure("invalid_manifest",ERROR_BACKOFF);
            if(info.has("releaseUrl")&&!releaseUrl.equals(info.optString("releaseUrl")))throw new Failure("invalid_manifest",ERROR_BACKOFF);
            latest.put("versionCode",integer(info.opt("versionCode")));
        }
        Object id=release.opt("id");String key=tag+"#"+(id instanceof Number?((Number)id).longValue():0)+":"+latest.optInt("versionCode");return latest.put("releaseKey",key);
    }
    private static JSONObject copy(JSONObject s){return s==null?new JSONObject():new JSONObject(s.toString());}
    private JSONObject load()throws Exception{return copy(store.read());}
    private boolean due(JSONObject s,long now){long last=s.optLong("lastChecked",0);return last<=0||last>now||now-last>=INTERVAL;}
    private boolean blocked(JSONObject s,long now){long until=s.optLong("retryAfter",0);return until>now&&until-now<=MAX_BACKOFF;}
    private JSONObject view(JSONObject s,boolean manual,boolean cached){
        JSONObject out=new JSONObject().put("sequence",eventSequence.incrementAndGet()).put("currentVersion",currentName).put("currentVersionCode",currentCode).put("busy",busy).put("manual",manual).put("cached",cached).put("lastChecked",s.optLong("lastChecked",0)).put("retryAfter",s.optLong("retryAfter",0)).put("error",s.optString("error","")).put("downloadUrl",DOWNLOAD).put("intervalHours",9).put("updateAvailable",false).put("showPrompt",false);
        JSONObject latest=s.optJSONObject("latest");if(latest!=null){try{
            int code=latest.optInt("versionCode",0);int comparison=code>0?Integer.compare(code,currentCode):compareVersions(latest.optString("versionName"),currentName);
            out.put("latestVersion",latest.optString("versionName")).put("summary",latest.optString("summary")).put("releaseKey",latest.optString("releaseKey")).put("updateAvailable",comparison>0).put("aheadOfRelease",comparison<0);
            long now=clock.now(),until=s.optLong("dismissedUntil",0);boolean ignored=latest.optString("releaseKey").equals(s.optString("dismissedKey"))&&until>now&&until-now<=INTERVAL;
            out.put("showPrompt",comparison>0&&out.optString("error").isEmpty()&&(manual||!ignored));
        }catch(Exception invalid){out.put("error","invalid_cache");}}
        return out;
    }
    synchronized JSONObject snapshot(boolean manual){try{return view(load(),manual,true);}catch(Exception e){return error("storage_error",manual);}}
    private JSONObject error(String code,boolean manual){return new JSONObject().put("sequence",eventSequence.incrementAndGet()).put("currentVersion",currentName).put("currentVersionCode",currentCode).put("error",code).put("busy",false).put("manual",manual).put("updateAvailable",false).put("showPrompt",false).put("downloadUrl",DOWNLOAD);}
    JSONObject check(boolean manual){
        try{if(currentCode<=0)throw new IOException();normalized(currentName);}catch(Exception invalid){return error("current_version_unknown",manual);}
        JSONObject start;long when=clock.now();
        synchronized(this){try{start=load();}catch(Exception e){return error("storage_error",manual);}if(busy)return view(start,manual,true);if(blocked(start,when)&&(!manual||"rate_limited".equals(start.optString("error"))))return view(start,manual,true);if(!manual&&!due(start,when))return view(start,false,true);busy=true;}
        JSONObject latest=null;String failure="";long delay=ERROR_BACKOFF;
        try{latest=fetch();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();}
        catch(Exception e){failure=e instanceof Failure?((Failure)e).code:Thread.currentThread().isInterrupted()?"cancelled":"network_error";if(e instanceof Failure)delay=Math.max(ERROR_BACKOFF,Math.min(MAX_BACKOFF,((Failure)e).delay));}
        synchronized(this){busy=false;try{JSONObject state=load();if(failure.equals("cancelled"))return view(state,manual,true);long now=clock.now();state.put("lastAttempt",now);if(latest!=null){state.put("latest",latest).put("lastChecked",now).put("retryAfter",0).put("error","");}else{state.put("error",failure).put("retryAfter",now+delay);}store.write(state);return view(state,manual,false);}catch(Exception e){return error("storage_error",manual);}}
    }
    synchronized JSONObject dismiss(String key){try{JSONObject s=load(),latest=s.optJSONObject("latest");if(latest!=null&&latest.optString("releaseKey").equals(key)){s.put("dismissedKey",key).put("dismissedUntil",clock.now()+INTERVAL);store.write(s);}return view(s,false,true);}catch(Exception e){return error("storage_error",false);}}
}
