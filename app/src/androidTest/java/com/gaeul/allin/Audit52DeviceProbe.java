package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Android app_process probe: loads the installed RELEASE APK, never the real account directory.
 * Fault injection lives only in this separate process; all upstream credentials are synthetic.
 */
public final class Audit52DeviceProbe {
    static File root;
    static Field field(Class<?> c,String n)throws Exception {Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    static Object get(Class<?> c,String n)throws Exception{return field(c,n).get(null);}
    static void set(Object o,String n,Object v)throws Exception{field(o.getClass(),n).set(o,v);}
    static Method method(Class<?> c,String n,Class<?>...a)throws Exception{Method m=c.getDeclaredMethod(n,a);m.setAccessible(true);return m;}
    static GatewayServer gateway(JSONObject cfg)throws Exception{
        Class<?> c=Class.forName("sun.misc.Unsafe");Object u=field(c,"theUnsafe").get(null);
        GatewayServer g=(GatewayServer)c.getMethod("allocateInstance",Class.class).invoke(u,GatewayServer.class);
        set(g,"configJson",cfg);return g;
    }
    static JSONObject config(){return new JSONObject().put("providers",new JSONArray()).put("middleware",new JSONObject());}
    static UsageLedger.Row row(int i){UsageLedger.Row r=new UsageLedger.Row();r.t=System.currentTimeMillis();r.prov="synthetic";r.model="probe";r.requestId="audit-"+i;r.in=3;r.out=2;r.usageSource="known";r.status=200;return r;}
    static void output(String label,JSONObject value){System.out.println("AUDIT_ANDROID "+label+" "+value.toString());}
    static File area(String name){File f=new File(root,name);if(!f.mkdirs())throw new IllegalStateException("fresh test area required");return f;}
    static void remove(File f){File[] a=f.listFiles();if(a!=null)for(File x:a)remove(x);f.delete();}
    static int retained()throws Exception{int n=0;for(Object v:((Map<?,?>)get(UsageLedger.class,"DAYS")).values())n+=((List<?>)v).size();return n;}
    static void ledger()throws Exception{
        File dir=area("ledger");File blocker=new File(dir,"usage");try(FileOutputStream o=new FileOutputStream(blocker)){o.write(1);}
        UsageLedger.init(dir);long heap0=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        for(int i=0;i<10000;i++)UsageLedger.record(row(i));
        output("02_failure",new JSONObject().put("writes",10000).put("pending",((List<?>)get(UsageLedger.class,"unpersisted")).size()).put("retained",retained()).put("heapDelta",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-heap0));
        blocker.delete();blocker.mkdir();UsageLedger.record(row(10000));
        output("02_recovery",new JSONObject().put("pending",((List<?>)get(UsageLedger.class,"unpersisted")).size()).put("retained",retained()));
        File rotate=area("rotation");UsageLedger.init(rotate);UsageLedger.record(row(1));
        File before=(File)get(UsageLedger.class,"currentFile");method(UsageLedger.class,"closeWriter").invoke(null);
        try(RandomAccessFile f=new RandomAccessFile(before,"rw")){f.setLength(32L*1024*1024+1);f.seek(f.length()-1);f.write('\n');}
        long prior=before.length();UsageLedger.record(row(2));UsageLedger.record(row(3));
        File after=(File)get(UsageLedger.class,"currentFile");
        output("09_rotation",new JSONObject().put("sameFile",before.equals(after)).put("oldBytes",prior).put("newBytes",after.length()).put("files",new File(rotate,"usage").list().length));
        method(UsageLedger.class,"closeWriter").invoke(null);
    }
    static void checkin()throws Exception{
        final JSONObject[] cfg={config()};JSONObject acct=new JSONObject().put("id","synthetic").put("token","SYNTHETIC_NOT_A_CREDENTIAL").put("enabled",true);
        cfg[0].getJSONArray("providers").put(new JSONObject().put("id","loomy").put("accounts",new JSONArray().put(acct)));
        final Object lock=new Object();final CountDownLatch persisted=new CountDownLatch(1),resume=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();final AtomicBoolean paused=new AtomicBoolean();
        CheckinCenter.Host h=new CheckinCenter.Host(){public Object lock(){return lock;}public JSONObject config(){return cfg[0];}public boolean save(JSONObject next){cfg[0]=next;persisted.countDown();return true;}public boolean retired(){
            if(persisted.getCount()==0&&!Thread.holdsLock(lock)&&paused.compareAndSet(false,true)){try{resume.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return false;}};
        CheckinCenter c=new CheckinCenter(h,(p,a)->{calls.incrementAndGet();return new JSONObject().put("outcome","available");});
        final JSONObject[] result={null};final Throwable[] fail={null};Thread t=new Thread(()->{try{result[0]=c.runOne("loomy","synthetic",false);}catch(Throwable e){fail[0]=e;}});t.start();
        if(!persisted.await(5,TimeUnit.SECONDS))throw new AssertionError("intent missing");
        synchronized(lock){cfg[0].getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).put("enabled",false);}
        resume.countDown();t.join(6000);c.close();
        output("06_checkin",new JSONObject().put("disabledBeforeSend",true).put("transports",calls.get()).put("outcome",result[0]==null?"exception":result[0].optString("outcome")).put("threadEnded",!t.isAlive()));
    }
    static NanoHTTPD.Response stream(InputStream in,UsageLedger.Row r)throws Exception{
        ((ThreadLocal<UsageLedger.Row>)get(GatewayServer.class,"U_ROW")).set(r);
        try{return (NanoHTTPD.Response)method(GatewayServer.class,"streamForwardResponse",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class).invoke(gateway(config()),in,null,null,null);}
        finally{((ThreadLocal<?>)get(GatewayServer.class,"U_ROW")).remove();}
    }
    static String drain(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("probe capture cap");out.write(b,0,n);}return out.toString("UTF-8");}
    static final String FINISH="data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n";
    static void sse()throws Exception{
        UsageLedger.init(area("sse"));final List<UsageLedger.Row> rows=new ArrayList<>();UsageLedger.setHooks(rows::add);
        byte[] bytes=FINISH.getBytes(StandardCharsets.UTF_8);
        InputStream faulty=new InputStream(){int p;public int read()throws IOException{if(p==bytes.length)throw new IOException("synthetic tail fault");return bytes[p++]&255;}public int read(byte[] b,int o,int n)throws IOException{if(p==bytes.length)throw new IOException("synthetic tail fault");int m=Math.min(n,bytes.length-p);System.arraycopy(bytes,p,b,o,m);p+=m;return m;}};
        NanoHTTPD.Response r=stream(faulty,row(1));String wire=drain(r.getData());r.getData().close();
        UsageLedger.Row recorded=rows.get(0);output("07_tail",new JSONObject().put("clientError",wire.contains("Upstream stream read failed")).put("rows",rows.size()).put("status",recorded.status).put("failed",recorded.failed()).put("usage",recorded.in+recorded.out));
        String multi="data: {\"choices\":\n"+"data: [{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        r=stream(new ByteArrayInputStream(multi.getBytes(StandardCharsets.UTF_8)),row(2));wire=drain(r.getData());r.getData().close();
        output("08_multiline",new JSONObject().put("joinedValid",new JSONObject("{\"choices\":\n[{\"finish_reason\":\"stop\"}]}").has("choices")).put("incompleteError",wire.contains("ended before a finish reason")).put("done",wire.contains("[DONE]")));
    }
    static void longline()throws Exception{
        final long[] consumed={0};InputStream endless=new InputStream(){public int read(){if(consumed[0]>=64L*1024*1024)return -1;consumed[0]++;return 'x';}public int read(byte[] b,int o,int n){int m=(int)Math.min(n,64L*1024*1024-consumed[0]);if(m==0)return -1;Arrays.fill(b,o,o+m,(byte)'x');consumed[0]+=m;return m;}};
        boolean oom=false,error=false;String type="";try{NanoHTTPD.Response r=stream(endless,null);byte[] b=new byte[4096];int n;while((n=r.getData().read(b))!=-1){if(new String(b,0,n,StandardCharsets.UTF_8).contains("error"))error=true;}r.getData().close();}catch(OutOfMemoryError e){oom=true;}catch(Throwable e){type=e.getClass().getSimpleName();}
        output("01_longline",new JSONObject().put("oom",oom).put("error",error).put("exceptionType",type).put("consumed",consumed[0]).put("maxHeap",Runtime.getRuntime().maxMemory()));
    }
    static volatile String httpMode="";static final AtomicInteger sends=new AtomicInteger();static volatile boolean serializedBearer;
    static void fakeHttp(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){protected URLConnection openConnection(URL u){return conn(u);}protected URLConnection openConnection(URL u,java.net.Proxy p){return conn(u);}});}
    static HttpURLConnection conn(URL url){return new HttpURLConnection(url){
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public int getResponseCode(){sends.incrementAndGet();String a=getRequestProperty("Authorization");if(a!=null&&a.startsWith("Bearer {"))serializedBearer=true;return httpMode.equals("qoder-error")?403:200;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){String text="{}";if(url.getPath().endsWith("/poll")){if(httpMode.equals("qoder-ok"))text="{\"token\":\"AUDIT52_TOKEN_SENTINEL_NOT_REAL\",\"refresh_token\":\"AUDIT52_REFRESH_SENTINEL_NOT_REAL\"}";else if(httpMode.equals("qoder-bad"))text="AUDIT52_MALFORMED_SENTINEL_NOT_REAL";else text="AUDIT52_ERROR_SENTINEL_NOT_REAL";}return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
        public InputStream getErrorStream(){return getInputStream();}
    };}
    static void qoder()throws Exception{fakeHttp();QoderAuth.Flow f=new QoderAuth.Flow();f.nonce="synthetic";f.verifier="synthetic";f.machineId="synthetic";
        for(String m:new String[]{"qoder-ok","qoder-error","qoder-bad"}){httpMode=m;QoderAuth.PollResult p=QoderAuth.poll(f);output("03_"+m,new JSONObject().put("status",p.status));}
        output("03_log_pid",new JSONObject().put("pid",android.os.Process.myPid()));}
    static void zcode()throws Exception{fakeHttp();httpMode="zcode";JSONObject cfg=config();cfg.getJSONArray("providers").put(new JSONObject().put("id","zcode").put("enabled",false).put("baseUrl","https://synthetic.invalid/v1").put("keys",new JSONArray().put(new JSONObject().put("key","SYNTHETIC_NOT_REAL").put("off",true))));
        method(GatewayServer.class,"calibrateZCodeBaseAsync").invoke(gateway(cfg));
        for(int i=0;i<60&&sends.get()==0;i++)Thread.sleep(25);
        output("10_calibration",new JSONObject().put("providerDisabled",true).put("keyOff",true).put("sends",sends.get()).put("objectBearer",serializedBearer));}
    static void ptr()throws Exception {
        try {
            Field impl=field(InetAddress.class,"impl");Object original=impl.get(null);Class<?> api=impl.getType();
            AtomicInteger ptr=new AtomicInteger(),dns=new AtomicInteger();
            Object injected=java.lang.reflect.Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
                if(m.getName().equals("getHostByAddr")){ptr.incrementAndGet();Thread.sleep(350);throw new UnknownHostException("synthetic delayed PTR");}
                if(m.getName().equals("lookupAllHostAddr")&&a!=null&&String.valueOf(a[0]).contains("audit52.invalid")){dns.incrementAndGet();Thread.sleep(350);throw new UnknownHostException("synthetic delayed DNS");}
                try{m.setAccessible(true);return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            impl.set(null,injected);
            try {
                GatewayServer g=gateway(config());g.setAsyncRunner(new GatewayServer.BoundedAsyncRunner(8));
                g.setTempFileManagerFactory(()->new NanoHTTPD.DefaultTempFileManager());
                for(byte[] bytes:new byte[][]{{(byte)192,0,2,17},{0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,17}}){
                    final InetAddress remote=InetAddress.getByAddress(bytes);final AtomicBoolean closed=new AtomicBoolean();
                    Socket socket=new Socket(){public InetAddress getInetAddress(){return remote;}public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public void close(){closed.set(true);}public boolean isClosed(){return closed.get();}};
                    int before=ptr.get();long at=System.nanoTime();
                    Object handler=method(GatewayServer.class,"createClientHandler",Socket.class,InputStream.class).invoke(g,socket,socket.getInputStream());
                    ((Runnable)handler).run();
                    output("04_ptr",new JSONObject().put("family",bytes.length==4?"IPv4":"IPv6").put("resolverCalls",ptr.get()-before).put("elapsedMs",(System.nanoTime()-at)/1000000).put("closed",closed.get()).put("loopback",remote.isLoopbackAddress()));
                }
                long at=System.nanoTime();try{NetworkPolicy.parse(new JSONObject().put("mode","direct")).connectTunnel("audit52.invalid",443,50,50);}catch(IOException expected){}
                output("R2_dns",new JSONObject().put("connectBudgetMs",50).put("elapsedMs",(System.nanoTime()-at)/1000000).put("resolverCalls",dns.get()));
            }finally{impl.set(null,original);}
        }catch(Throwable e){output("04_probe_unavailable",new JSONObject().put("type",e.getClass().getSimpleName()).put("message",String.valueOf(e.getMessage())));}
    }
    public static void main(String[] args)throws Exception{
        root=new File(args[1]);if(!root.getPath().startsWith("/data/local/tmp/allin-audit52-"))throw new SecurityException("isolated directory required");root.mkdirs();
        try{switch(args[0]){case "ptr":ptr();break;case "ledger":ledger();break;case "checkin":checkin();break;case "sse":sse();break;case "longline":longline();break;case "qoder":qoder();break;case "zcode":zcode();break;default:throw new IllegalArgumentException();}}
        finally{try{method(UsageLedger.class,"closeWriter").invoke(null);}catch(Throwable ignored){}remove(root);}
    }
}
