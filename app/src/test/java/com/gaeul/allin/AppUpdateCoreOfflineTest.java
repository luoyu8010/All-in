package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Memory-only release/cache/time fixtures: never reads accounts or changes the real repository. */
public final class AppUpdateCoreOfflineTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static final class Mem implements AppUpdateCore.Store {JSONObject state=new JSONObject();boolean fail;int writes;public JSONObject read(){return state;}public void write(JSONObject s)throws Exception{if(fail)throw new IOException();state=new JSONObject(s.toString());writes++;}}
    static final class Time implements AppUpdateCore.Clock {long time=1000000;public long now(){return time;}}
    static JSONObject release(String tag){return new JSONObject().put("id",10).put("draft",false).put("prerelease",false).put("tag_name",tag).put("html_url","https://github.com/xunianooo/All-in/releases/tag/"+tag).put("body","# 更新\n- 修复功能\n- 体验优化").put("assets",new JSONArray());}
    static final class Net implements AppUpdateCore.Transport {JSONObject data=release("v1.2.45-test"),manifest;int calls,status=200;long retry;Exception failure;public AppUpdateCore.Reply get(String url,int limit)throws Exception{calls++;if(failure!=null)throw failure;if(url.equals(AppUpdateCore.API))return new AppUpdateCore.Reply(status,data.toString(),retry);return new AppUpdateCore.Reply(status,manifest.toString(),retry);}}
    static AppUpdateCore core(Mem s,Net n,Time t){return new AppUpdateCore(s,n,t,50,"1.2.44-test");}
    static void versions()throws Exception {
        for(String[] v:new String[][]{{"1.2.9","1.2.10"},{"v1.2.41-test","1.2.44-test"},{"1.9.99","1.10.0"},{"1.2.44-test","1.2.44"}})check(AppUpdateCore.compareVersions(v[0],v[1])<=0,"numeric version ordering");
        check(AppUpdateCore.compareVersions("1.2.1000000000000","1.2.999999999999")>0,"large numbers do not overflow");
        for(String s:new String[]{"", "latest", "1.2", "1.2.3<script>","https://evil/1.2.3","1.2.3-beta"}){boolean refused=false;try{AppUpdateCore.normalized(s);}catch(IOException e){refused=true;}check(refused,"invalid tag rejected");}
        for(Object o:new Object[]{"51",true,0,-1,1.5,2147483648L}){boolean refused=false;try{AppUpdateCore.integer(o);}catch(IOException e){refused=true;}check(refused,"invalid versionCode rejected");}
    }
    static void cache()throws Exception {
        Mem s=new Mem();Net n=new Net();Time t=new Time();AppUpdateCore c=core(s,n,t);JSONObject first=c.check(false);check(first.getBoolean("updateAvailable")&&first.getBoolean("showPrompt"),"first newer release prompts");check(n.calls==1&&s.writes==1,"one public request and durable result");
        check(c.snapshot(false).getLong("sequence")>first.getLong("sequence"),"result sequence increases for safe callback ordering");String key=first.getString("releaseKey");c.dismiss(key);check(!c.snapshot(false).getBoolean("showPrompt"),"later blocks repeated automatic popup");check(c.snapshot(true).getBoolean("showPrompt"),"manual still reveals dismissed update");
        t.time+=AppUpdateCore.INTERVAL-1;c.check(false);check(n.calls==1,"less than 9 hours uses cache");t.time++;c.check(false);check(n.calls==2,"exact 9 hours rechecks");check(c.snapshot(false).getBoolean("showPrompt"),"later cooldown expires");
        c.check(true);check(n.calls==3,"manual bypasses 9-hour cache");
        c.dismiss(c.snapshot(false).getString("releaseKey"));n.data=release("v1.2.46-test");JSONObject next=c.check(true);check(next.getBoolean("showPrompt"),"new release not suppressed by old dismissal");
        n.data=release("v1.2.41-test");JSONObject old=c.check(true);check(!old.getBoolean("updateAvailable")&&old.getBoolean("aheadOfRelease"),"local 50 never offered public 47 as upgrade");
        n.data=release("v1.2.44-test");check(!c.check(true).getBoolean("updateAvailable"),"equal version no popup");t.time-=100000000;c.check(false);check(n.calls>=6,"clock rollback cannot suppress checks forever");
    }
    static void manifest()throws Exception {
        Mem s=new Mem();Net n=new Net();Time t=new Time();n.data.getJSONArray("assets").put(new JSONObject().put("name","update.json").put("browser_download_url","https://github.com/xunianooo/All-in/releases/download/v1.2.45-test/update.json"));
        n.manifest=new JSONObject().put("schema",1).put("tag","v1.2.45-test").put("versionName","1.2.45-test").put("versionCode",51);AppUpdateCore c=core(s,n,t);check(c.check(true).getBoolean("updateAvailable")&&n.calls==2,"optional versionCode manifest used");n.manifest.put("versionCode",49);check(!c.check(true).getBoolean("updateAvailable"),"manifest code takes priority, no downgrade");
        n.manifest.put("schema",1.5);check(!c.check(true).optString("error").isEmpty(),"fractional schema refused");n.manifest.put("schema",1);n.manifest.put("versionCode",1.5);check(!c.check(true).optString("error").isEmpty(),"fractional code refused");
        t.time+=AppUpdateCore.ERROR_BACKOFF;n.manifest.put("versionCode",51).put("versionName","1.2.999");check(!c.check(true).optString("error").isEmpty(),"manifest/tag mismatch refused");
        t.time+=AppUpdateCore.ERROR_BACKOFF;n.data.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://evil.example/update.json");check(!c.check(true).getBoolean("showPrompt"),"foreign manifest URL cannot prompt");
    }
    static void errors()throws Exception {
        for(String name:new String[]{"", "invalid"}){Mem m=new Mem();Net net=new Net();Time time=new Time();JSONObject unknown=new AppUpdateCore(m,net,time,0,name).check(true);check(unknown.optString("error").equals("current_version_unknown")&&net.calls==0,"unknown installed version never requests or offers update");}

        for(int status:new int[]{403,429,404,500}){Mem s=new Mem();Net n=new Net();Time t=new Time();n.status=status;n.retry=60000;AppUpdateCore c=core(s,n,t);JSONObject result=c.check(true);check(!result.optString("error").isEmpty()&&!result.optBoolean("showPrompt"),"HTTP errors never look current/successful");c.check(true);check(n.calls==(status==403||status==429?1:2),"manual only respects server rate limit, not ordinary failures");t.time+=AppUpdateCore.ERROR_BACKOFF;c.check(false);check(n.calls==(status==403||status==429?2:3),"automatic backoff permits bounded retry");}
        Mem s=new Mem();Net n=new Net();Time t=new Time();AppUpdateCore c=core(s,n,t);c.check(false);n.failure=new java.net.SocketTimeoutException();JSONObject r=c.check(true);check(r.optString("error").equals("network_error")&&!r.optBoolean("showPrompt"),"timeout not masked as successful cache");n.failure=null;int prior=n.calls;check(c.check(true).optString("error").isEmpty()&&n.calls==prior+1,"manual retry immediately recovers after ordinary network failure");
        s=new Mem();n=new Net();t=new Time();s.fail=true;check(core(s,n,t).check(true).optString("error").equals("storage_error")&&s.state.length()==0,"failed preferences do not publish cached success");
        for(String field:new String[]{"draft","prerelease"}){s=new Mem();n=new Net();t=new Time();n.data.put(field,true);check(!core(s,n,t).check(true).getBoolean("showPrompt"),"no draft or prerelease prompts");}
        s=new Mem();n=new Net();t=new Time();n.data.put("html_url","javascript:alert(1)");check(!core(s,n,t).check(true).getBoolean("showPrompt"),"release URL is repository-bound");
        String clean=AppUpdateCore.summary("<img src=x onerror=alert(1)>\n[说明](javascript:evil)\n<script>fake</script>\n第四行");check(!clean.contains("<img")&&!clean.contains("javascript:")&&clean.split("\n").length<=3,"summary is short plain text");
    }
    static void concurrency()throws Exception {
        Mem s=new Mem();Time t=new Time();AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),go=new CountDownLatch(1);AppUpdateCore c=new AppUpdateCore(s,(url,limit)->{calls.incrementAndGet();entered.countDown();if(!go.await(3,TimeUnit.SECONDS))throw new IOException();return new AppUpdateCore.Reply(200,release("v1.2.45-test").toString(),0);},t,50,"1.2.44-test");AtomicReference<JSONObject> result=new AtomicReference<>();Thread worker=new Thread(()->result.set(c.check(false)));worker.start();check(entered.await(2,TimeUnit.SECONDS),"network operation paused");check(c.check(true).getBoolean("busy")&&calls.get()==1,"concurrent manual/auto never double send");go.countDown();worker.join(3000);check(!worker.isAlive()&&result.get().getBoolean("updateAvailable"),"first operation completes safely");
    }
    static void transport()throws Exception {
        long now=100000000;check(AppUpdateManager.nextDelay(new JSONObject(),now)==AppUpdateCore.INTERVAL,"initial auto deadline is nine hours");check(AppUpdateManager.nextDelay(new JSONObject().put("lastChecked",now-3*3600000),now)==6*3600000,"manual success reanchors exact next deadline");check(AppUpdateManager.nextDelay(new JSONObject().put("error","network_error").put("retryAfter",now+AppUpdateCore.ERROR_BACKOFF),now)==AppUpdateCore.ERROR_BACKOFF,"initial failure uses retry deadline");check(AppUpdateManager.nextDelay(new JSONObject().put("lastChecked",now+1),now)==1000,"clock rollback does not postpone forever");

        check(AppUpdateTransport.allowed(AppUpdateCore.API,false),"only intended API allowed");for(String url:new String[]{"http://api.github.com/repos/xunianooo/All-in/releases/latest","https://api.github.com.evil/repos/xunianooo/All-in/releases/latest","https://user:pass@api.github.com/repos/xunianooo/All-in/releases/latest","https://github.com/evil/All-in/releases/download/v1.2.3/update.json","https://evil.example/update.json"})check(!AppUpdateTransport.allowed(url,false),"untrusted endpoint refused");
        String head=new String(AppUpdateTransport.requestBytes(new URI(AppUpdateCore.API)),java.nio.charset.StandardCharsets.US_ASCII);check(head.startsWith("GET /repos/xunianooo/All-in/releases/latest HTTP/1.1"),"exact public endpoint");for(String h:new String[]{"Authorization:","Cookie:","X-All-In-Management:","Proxy-Authorization:"})check(!head.contains(h),"no private headers");check(head.contains("Accept-Encoding: identity")&&head.contains("Connection: close"),"bounded ordinary public request");
    }
    public static void main(String[] args)throws Exception{versions();cache();manifest();errors();concurrency();transport();System.out.println("AppUpdateCoreOfflineTest PASS: "+checks+" checks");}
}
