package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real gateway handlers / refresh CAS / atomic disk writes, synthetic accounts only. */
public final class ConfigConsistencyOfflineTest {
    private static int checks;
    private static final Method ENSURE, SAVE;
    static {
        try {
            ENSURE=GatewayServer.class.getDeclaredMethod("ensureStoredToken",String.class,JSONObject.class,String.class);
            SAVE=GatewayServer.class.getDeclaredMethod("persistConfigStrict",JSONObject.class);
            ENSURE.setAccessible(true);SAVE.setAccessible(true);
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject copy(JSONObject o){return new JSONObject(o.toString());}
    static Object invoke(Method m,Object receiver,Object... args)throws Exception {
        try{return m.invoke(receiver,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;throw new RuntimeException(t);}
    }
    static JSONObject root(GatewayServer s)throws Exception{Field f=GatewayServer.class.getDeclaredField("configJson");f.setAccessible(true);return (JSONObject)f.get(s);}
    static void field(GatewayServer s,String key,Object value)throws Exception{Field f=GatewayServer.class.getDeclaredField(key);f.setAccessible(true);f.set(s,value);}
    static JSONObject provider(JSONObject config,String id){JSONArray a=config.getJSONArray("providers");for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).optString("id")))return a.getJSONObject(i);throw new AssertionError("missing provider");}
    static JSONObject account(JSONObject config,String p,String id){JSONArray a=provider(config,p).getJSONArray("accounts");for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).optString("id")))return a.getJSONObject(i);return null;}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("uid",id).put("name",id).put("type","oauth").put("enabled",true).put("access","synthetic-old-"+id).put("token","synthetic-old-"+id).put("refresh","synthetic-refresh-"+id).put("expires",1L);}
    static final class Fixture implements AutoCloseable {
        final Path dir=Files.createTempDirectory("config-consistency-");final File file=dir.resolve("config.json").toFile();final GatewayServer server;
        Fixture()throws Exception {
            JSONArray ps=new JSONArray();for(String p:new String[]{"github-copilot","antigravity","workbuddy","workbuddy-ai"})ps.put(new JSONObject().put("id",p).put("enabled",true).put("protocol","openai").put("models",new JSONArray().put("m")).put("keys",new JSONArray()).put("accounts",new JSONArray().put(account("a")).put(account("b"))));
            JSONObject config=new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",ps).put("groups",new JSONArray()).put("checkin",new JSONObject().put("autoEnabled",false));
            server=RoutingSetupOfflineTest.server(config);field(server,"configFile",file);AtomicConfigFile.write(file,config.toString(2));
        }
        JSONObject disk()throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        public void close()throws Exception{GatewayServer.setConfigWriteHookForTests(null);server.setTokenRefreshForTests(null);server.stop();try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}NetworkPolicy.configure(null);}
    }
    static final class Reply {final int status;final JSONObject json;Reply(NanoHTTPD.Response r)throws Exception{status=r.getStatus().getRequestStatus();try(InputStream in=r.getData()){json=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}}
    static Reply call(Fixture f,String path,String method,JSONObject body)throws Exception{return new Reply(f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1",path,method,body==null?"":body.toString(),null)));}
    static JSONObject renew(Fixture f,String p,String id)throws Exception{return (JSONObject)invoke(ENSURE,f.server,p,copy(account(root(f.server),p,id)),null);}
    static JSONObject candidate(JSONObject a){return copy(a).put("access","synthetic-new-"+a.getString("id")).put("token","synthetic-new-"+a.getString("id")).put("expires",System.currentTimeMillis()+3600000L);}
    static void await(CountDownLatch latch)throws Exception{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}
    static void join(Thread t)throws Exception{t.join(10000);if(t.isAlive()){t.interrupt();throw new AssertionError("worker did not exit");}}
    static void blocked(Thread t)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(t.isAlive()&&t.getState()!=Thread.State.BLOCKED&&System.nanoTime()<end)Thread.yield();check(t.getState()==Thread.State.BLOCKED,"management waits on the held config monitor");}
    /** Pauses the REAL refresh disk write, after its snapshot is serialized and while holding the config lock. */
    static final class GateFile extends File {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean once=new AtomicBoolean();
        GateFile(File f){super(f.getPath());}
        @Override public File getAbsoluteFile(){if(Thread.currentThread().getName().equals("refresh-commit")&&once.compareAndSet(false,true)){entered.countDown();try{await(release);}catch(Exception e){throw new IllegalStateException(e);}}return super.getAbsoluteFile();}
    }
    static JSONObject patch(String field){switch(field){case "privacy":return new JSONObject().put("privacy",new JSONObject().put("enabled",true));case "middleware":return new JSONObject().put("marker","new-setting");case "exposed":return new JSONObject().put("enabled",true).put("items",new JSONArray().put("github-copilot/m"));default:return new JSONObject().put("groups",new JSONArray().put(new JSONObject().put("id","new-group").put("targets",new JSONArray().put("github-copilot/m"))));}}
    static void crossedCommit(String setting)throws Exception {
        try(Fixture f=new Fixture()) {
            GateFile gate=new GateFile(f.file);field(f.server,"configFile",gate);f.server.setTokenRefreshForTests((a,force)->candidate(a));
            AtomicReference<Throwable> refreshError=new AtomicReference<>(),writeError=new AtomicReference<>();AtomicReference<Reply> response=new AtomicReference<>();
            Thread refresh=new Thread(()->{try{renew(f,"github-copilot","a");}catch(Throwable t){refreshError.set(t);}},"refresh-commit");
            Thread write=new Thread(()->{try{response.set(call(f,"/api/"+setting,"POST",patch(setting)));}catch(Throwable t){writeError.set(t);}},"management-write");
            refresh.start();try{await(gate.entered);write.start();blocked(write);}finally{gate.release.countDown();}
            join(refresh);join(write);if(writeError.get()!=null)throw new AssertionError(writeError.get());check(response.get()!=null&&response.get().status==200,setting+" accepted");
            check("synthetic-new-a".equals(account(f.disk(),"github-copilot","a").getString("access")),setting+" must not overwrite the committed fresh credential");
            check("synthetic-new-a".equals(account(root(f.server),"github-copilot","a").getString("access")),setting+" memory retains fresh credential");check(f.disk().has(setting),setting+" remains durable");
            if(refreshError.get()!=null)throw new AssertionError("refresh must still be usable after unrelated setting",refreshError.get());
        }
    }
    static void duringRefresh(String providerId,String change)throws Exception {
        try(Fixture f=new Fixture()) {
            AtomicInteger sends=new AtomicInteger();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
            f.server.setTokenRefreshForTests((a,force)->{sends.incrementAndGet();started.countDown();await(release);return candidate(a).put("refresh","synthetic-rotated-"+a.getString("id"));});
            Thread t=new Thread(()->{try{renew(f,providerId,"a");}catch(Throwable e){error.set(e);}},"refresh-network");t.start();await(started);
            try {
                if("settings".equals(change))check(call(f,"/api/guard","POST",new JSONObject().put("enabled",false)).status==200,"ordinary settings save");
                else if("checkin".equals(change)) {f.server.setCheckinTransportForTests(new CheckinCenter.Transport(){public JSONObject checkin(String p,JSONObject a){throw new AssertionError("no claims");}public JSONObject status(String p,JSONObject a){return new JSONObject().put("outcome","not_checked").put("verified",true).put("claimPossible",false);}});check(call(f,"/api/checkin/reconcile","POST",new JSONObject().put("provider","workbuddy").put("accountId","b")).status==200,"actual checkin record save");}
                else synchronized(f.server){JSONObject updated=copy(root(f.server));account(updated,providerId,"b").put("name","changed-other-account");invoke(SAVE,f.server,updated);}
            } finally {release.countDown();}
            join(t);if(error.get()!=null)throw new AssertionError(providerId+" refresh rejected after unrelated "+change,error.get());
            check(sends.get()==1,"one refresh send");check("synthetic-rotated-a".equals(account(f.disk(),providerId,"a").getString("refresh")),"new refresh durable after "+change);
            if("checkin".equals(change))check(f.disk().getJSONObject("checkin").getJSONObject("records").has("workbuddy:b"),"checkin preserved");
            if("other-account".equals(change))check("changed-other-account".equals(account(f.disk(),providerId,"b").optString("name")),"other account preserved");
        }
    }
    static void independentRefreshes()throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger sends=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();
            f.server.setTokenRefreshForTests((a,force)->{sends.incrementAndGet();started.countDown();await(release);return candidate(a);});
            Thread one=new Thread(()->{try{renew(f,"github-copilot","a");}catch(Throwable e){error.set(e);}}),two=new Thread(()->{try{renew(f,"github-copilot","b");}catch(Throwable e){error.set(e);}});
            one.start();two.start();try{await(started);}finally{release.countDown();}join(one);join(two);if(error.get()!=null)throw new AssertionError("independent refreshes conflict",error.get());
            check(sends.get()==2,"each account sends once");for(String id:new String[]{"a","b"})check(("synthetic-new-"+id).equals(account(f.disk(),"github-copilot",id).getString("access")),"both refreshes durable");
        }
    }
    static void staleFullConfig(String change)throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject stale=call(f,"/api/config","GET",null).json;
            synchronized(f.server){JSONObject next=copy(root(f.server));JSONObject p=provider(next,"workbuddy");
                switch(change){case "add":p.getJSONArray("accounts").put(account("c"));break;case "provider":next.getJSONArray("providers").put(new JSONObject().put("id","new-provider").put("accounts",new JSONArray()));break;case "disable":account(next,"workbuddy","a").put("enabled",false);break;case "delete":p.put("accounts",new JSONArray().put(account("b")));break;case "rotate":account(next,"workbuddy","b").put("refresh","synthetic-rotated");break;}
                invoke(SAVE,f.server,next);
            }
            byte[] before=Files.readAllBytes(f.file.toPath());Reply rejected=call(f,"/api/config","POST",stale);
            check(rejected.status==409,"stale full config rejected after "+change+", got "+rejected.status);check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"stale POST writes nothing");
            JSONObject fresh=call(f,"/api/config","GET",null).json;fresh.put("groups",new JSONArray());check(call(f,"/api/config","POST",fresh).status==200,"fresh full configuration accepted");
        }
    }
    static void failedSettings(String setting)throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject before=copy(root(f.server));byte[] disk=Files.readAllBytes(f.file.toPath());AtomicBoolean leaked=new AtomicBoolean();
            GatewayServer.setConfigWriteHookForTests(()->{try{leaked.set(!before.similar(root(f.server)));}catch(Exception e){throw new IllegalStateException(e);}throw new IllegalStateException("synthetic disk failure");});
            Reply r;try{r=call(f,"/api/"+setting,"POST",patch(setting));}finally{GatewayServer.setConfigWriteHookForTests(null);}
            check(r.status==500,"failed save is not success");check(!leaked.get(),setting+" failed mutation never published, even before disk failure");check(before.similar(root(f.server)),"memory unchanged on failed save");check(Arrays.equals(disk,Files.readAllBytes(f.file.toPath())),"disk unchanged on failed save");
        }
    }
    static void lateRefreshRejected(String change)throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger sends=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();
            f.server.setTokenRefreshForTests((a,force)->{sends.incrementAndGet();started.countDown();await(release);return candidate(a).put("refresh","synthetic-consumed-next");});
            Thread worker=new Thread(()->{try{renew(f,"workbuddy","a");}catch(Throwable e){error.set(e);}});worker.start();await(started);
            byte[] before;
            try { synchronized(f.server) {
                JSONObject original=copy(root(f.server)),next=copy(original);JSONObject p=provider(next,"workbuddy");
                if("delete-readd".equals(change)||"delete".equals(change))p.put("accounts",new JSONArray().put(account("b")));
                else if("disable-reenable".equals(change)||"disable".equals(change))account(next,"workbuddy","a").put("enabled",false);
                else if("replace".equals(change))account(next,"workbuddy","a").put("uid","different-owner");
                invoke(SAVE,f.server,next);
                if("delete-readd".equals(change)||"disable-reenable".equals(change))invoke(SAVE,f.server,original);
                if("retired".equals(change))field(f.server,"backupRetired",true);
                before=Files.readAllBytes(f.file.toPath());
            }}finally{release.countDown();}
            join(worker);check(error.get()!=null,"late rotation rejected after "+change);check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"late rotation does not change durable account after "+change);check(sends.get()==1,"exactly one upstream attempt");
            if("delete-readd".equals(change)||"disable-reenable".equals(change)){try{renew(f,"workbuddy","a");}catch(Exception expected){}check(sends.get()==1,"spent credential never resent after lifecycle conflict");}
        }
    }
    static void parallelSettings()throws Exception {
        try(Fixture f=new Fixture()) {
            GateFile gate=new GateFile(f.file);field(f.server,"configFile",gate);AtomicReference<Throwable> error=new AtomicReference<>();
            Thread first=new Thread(()->{try{check(call(f,"/api/privacy","POST",patch("privacy")).status==200,"first setting saved");}catch(Throwable e){error.set(e);}},"refresh-commit");
            Thread second=new Thread(()->{try{check(call(f,"/api/middleware","POST",patch("middleware")).status==200,"second setting saved");}catch(Throwable e){error.set(e);}},"second-setting");
            first.start();try{await(gate.entered);second.start();blocked(second);}finally{gate.release.countDown();}join(first);join(second);if(error.get()!=null)throw new AssertionError(error.get());
            check(f.disk().getJSONObject("privacy").getBoolean("enabled"),"first concurrent setting preserved");check("new-setting".equals(f.disk().getJSONObject("middleware").optString("marker")),"second concurrent setting preserved");
        }
    }
    static void revisionProtocol()throws Exception {
        try(Fixture f=new Fixture();Fixture other=new Fixture()) {
            JSONObject view=call(f,"/api/config","GET",null).json;String revision=view.getString("revision");byte[] before=Files.readAllBytes(f.file.toPath());
            JSONObject missing=copy(view);missing.remove("revision");check(call(f,"/api/config","POST",missing).status==409,"unconditional full overwrite refused");
            JSONObject wrong=copy(view).put("revision",call(other,"/api/config","GET",null).json.getString("revision"));check(call(f,"/api/config","POST",wrong).status==409,"revision belongs to this gateway");
            GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic write failure");});try{check(call(f,"/api/config","POST",view).status==500,"full save failure reported");}finally{GatewayServer.setConfigWriteHookForTests(null);}
            check(revision.equals(call(f,"/api/config","GET",null).json.optString("revision")),"failed write does not advance revision");check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"revision failures leave disk intact");
            view.remove("revision");view.put("expectedRevision",revision);check(call(f,"/api/config","POST",view).status==200,"explicit expectedRevision supported");check(!f.disk().has("revision")&&!f.disk().has("expectedRevision"),"API revision metadata never persisted");
        }
    }
    interface Test {void run()throws Exception;}
    static int run(String name,Test action){try{action.run();System.out.println("PASS "+name);return 0;}catch(Throwable t){System.err.println("FAIL "+name+": "+t);return 1;}}
    public static void main(String[] args)throws Exception {
        int failed=0;String group=args.length==0?"all":args[0];
        if("all".equals(group)||"transactions".equals(group))for(String s:new String[]{"privacy","middleware","exposed","groups"}){failed+=run("crossed "+s,()->crossedCommit(s));failed+=run("failure "+s,()->failedSettings(s));}
        if("all".equals(group)||"refresh".equals(group)){for(String p:new String[]{"github-copilot","antigravity","workbuddy","workbuddy-ai"})for(String c:new String[]{"settings","checkin","other-account"})failed+=run(p+" "+c,()->duringRefresh(p,c));failed+=run("independent refreshes",ConfigConsistencyOfflineTest::independentRefreshes);}
        if("all".equals(group)||"revision".equals(group))for(String c:new String[]{"add","provider","disable","delete","rotate"})failed+=run("revision "+c,()->staleFullConfig(c));
        if("all".equals(group)) {
            failed+=run("parallel settings",ConfigConsistencyOfflineTest::parallelSettings);
            failed+=run("revision protocol",ConfigConsistencyOfflineTest::revisionProtocol);
            for(String change:new String[]{"delete","disable","replace","delete-readd","disable-reenable","retired"})failed+=run("lifecycle "+change,()->lateRefreshRejected(change));
        }
        if(failed>0)throw new AssertionError(failed+" consistency scenarios failed; "+checks+" assertions evaluated");System.out.println("ConfigConsistencyOfflineTest PASS: "+checks+" checks");
    }
}
