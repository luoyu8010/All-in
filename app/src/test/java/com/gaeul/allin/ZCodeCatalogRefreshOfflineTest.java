package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic metadata lifecycle tests. Synthetic credentials; no provider or claims. */
public final class ZCodeCatalogRefreshOfflineTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final NetworkPolicy.Snapshot DIRECT=NetworkPolicy.parse(new JSONObject().put("mode","direct"));
    static void idle(ZCodeCatalogRefresh refresh) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(refresh.activeTasks()!=0&&System.nanoTime()<until)Thread.sleep(5);
        check(refresh.activeTasks()==0,"worker and cache lease settle");
    }
    static class Failure implements ZCodeGiftControl.Transport {
        final AtomicInteger calls=new AtomicInteger(),claims=new AtomicInteger();
        volatile NetworkPolicy.Snapshot policy;
        public JSONObject summary(String jwt,String device) throws Exception {
            calls.incrementAndGet();policy=NetworkPolicy.capture();
            return new JSONObject().put("ok",false).put("error","fixture network failure");
        }
        public JSONObject claim(String jwt,String device,String id){claims.incrementAndGet();throw new AssertionError("claim is forbidden");}
    }
    static void retryAndIdentity() throws Exception {
        AtomicLong now=new AtomicLong(1000);Failure transport=new Failure();ZCodeGiftControl.setTestHooks(null,transport);
        ZCodeCatalogRefresh refresh=new ZCodeCatalogRefresh(now::get,30000,5000);
        try {
            check(refresh.request("account-a","device-a",DIRECT,()->true),"first miss accepted");idle(refresh);
            check(transport.calls.get()==1&&transport.policy==DIRECT,"captured network scope used");
            check(ZCodeGiftControl.peekSummary("account-a","device-a")==null,"failure not cached as zero");
            now.set(30999);check(!refresh.request("account-a","device-a",DIRECT,()->true),"failure backoff");
            now.set(31000);check(refresh.request("account-a","device-a",DIRECT,()->true),"retry boundary opens");idle(refresh);
            check(transport.calls.get()==2,"one bounded retry");
            check(refresh.request("account-b","device-a",DIRECT,()->true),"other identity not cooled down");idle(refresh);
            check(transport.calls.get()==3,"other identity read");
            check(refresh.request("retired-account","device-a",DIRECT,()->false),"work can be admitted before state recheck");idle(refresh);
            check(transport.calls.get()==3,"changed account rejected before sending");
            AtomicInteger uncaught=new AtomicInteger();Thread.UncaughtExceptionHandler old=Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread,error)->uncaught.incrementAndGet());
            try {
                check(refresh.request("invalid-state","device-a",DIRECT,()->{throw new IllegalStateException("fixture state");}),"invalid state admitted for recheck");
                idle(refresh);check(uncaught.get()==0&&transport.calls.get()==3,"bad metadata state cannot crash the process or send");
            } finally {Thread.setDefaultUncaughtExceptionHandler(old);}

            java.lang.reflect.Field field=ZCodeCatalogRefresh.class.getDeclaredField("retryAt");field.setAccessible(true);
            for(Object key:((Map<?,?>)field.get(refresh)).keySet())check(key.toString().matches("[0-9a-f]{64}"),"retry keys contain no raw credential");
            check(transport.claims.get()==0,"no claims");
        } finally {refresh.close();idle(refresh);ZCodeGiftControl.setTestHooks(null,null);}
    }
    static void boundedAndStopped() throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger started=new AtomicInteger();
        ZCodeGiftControl.setTestHooks(null,new Failure(){
            public JSONObject summary(String jwt,String device)throws Exception{started.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);return new JSONObject().put("ok",false);}
        });
        ZCodeCatalogRefresh refresh=new ZCodeCatalogRefresh(()->1000,30000,5000);
        try {
            check(refresh.request("a","d",DIRECT,()->true),"first worker");
            check(refresh.request("b","d",DIRECT,()->true),"second identity worker");
            check(entered.await(1,TimeUnit.SECONDS),"both workers started");
            for(int i=0;i<20;i++)check(!refresh.request("a","d",DIRECT,()->true),"same account coalesced "+i);
            check(!refresh.request("c","d",DIRECT,()->true),"no unbounded third job or queue");
            check(started.get()==2&&refresh.activeTasks()==2,"at most two in flight");
            refresh.close();idle(refresh);
            check(!refresh.request("d","d",DIRECT,()->true),"stopped instance cannot restart work");
            check(started.get()==2,"stop does not issue another lookup");
        } finally {release.countDown();refresh.close();idle(refresh);ZCodeGiftControl.setTestHooks(null,null);}
    }
    static final AtomicInteger opened=new AtomicInteger(),disconnected=new AtomicInteger();
    static final CountDownLatch httpEntered=new CountDownLatch(1),httpRelease=new CountDownLatch(1);
    static final class DelayedConnection extends HttpURLConnection {
        volatile boolean stopped;
        DelayedConnection(URL url){super(url);opened.incrementAndGet();}
        public void connect(){}
        public boolean usingProxy(){return false;}
        public void disconnect(){stopped=true;disconnected.incrementAndGet();httpRelease.countDown();}
        public int getResponseCode() throws IOException {
            check("GET".equals(getRequestMethod()),"metadata transport is GET only");
            check(getConnectTimeout()<=250&&getReadTimeout()<=250,"production HTTP inherits lookup budget");
            httpEntered.countDown();
            try {httpRelease.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("fixture interrupted");}
            throw new IOException(stopped?"fixture disconnected":"fixture timeout");
        }
    }
    static void productionHttpCancellation() throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException{
                if(!"zcode.z.ai".equals(url.getHost()))throw new IOException("test refuses external URL");
                return new DelayedConnection(url);
            }
            protected URLConnection openConnection(URL url,Proxy proxy)throws IOException{return openConnection(url);}
        }:null);
        ZCodeGiftControl.setTestHooks(null,null);
        ZCodeCatalogRefresh refresh=new ZCodeCatalogRefresh(()->1000,30000,250);
        try {
            check(refresh.request("fixture-session","fixture-device",DIRECT,()->true),"real production summary scheduled");
            check(httpEntered.await(2,TimeUnit.SECONDS),"production callEnvelope reached delayed HTTP");idle(refresh);
            check(disconnected.get()>0,"budget expiry disconnects production connection");
            check(opened.get()==1,"cancelled feed does not start balance or DNS diagnostics");
            check(ZCodeGiftControl.peekSummary("fixture-session","fixture-device")==null,"cancelled metadata is never cached as successful");
        } finally {httpRelease.countDown();refresh.close();idle(refresh);ZCodeGiftControl.setTestHooks(null,null);}
    }
    public static void main(String[] args)throws Exception {
        retryAndIdentity();boundedAndStopped();productionHttpCancellation();
        System.out.println("ZCodeCatalogRefreshOfflineTest PASS "+checks+" checks");
    }
}
