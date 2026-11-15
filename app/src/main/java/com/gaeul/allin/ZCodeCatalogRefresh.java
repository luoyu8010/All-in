package com.gaeul.allin;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Metadata-only refreshes. No work queue, no claims, no blocking work on the listing thread. */
final class ZCodeCatalogRefresh implements AutoCloseable {
    interface Clock { long now(); }
    private static final ThreadLocal<Lookup> CURRENT = new ThreadLocal<>();
    private final Clock clock;
    private final long retryMillis, budgetMillis;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Map<String,Lookup> active = new HashMap<>();
    // Fingerprints only. Raw credentials exist only in the at-most-two running jobs.
    private final LinkedHashMap<String,Long> retryAt = new LinkedHashMap<>(16,.75f,true);
    private boolean closed;

    ZCodeCatalogRefresh() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),30000,30000); }
    ZCodeCatalogRefresh(Clock clock,long retryMillis,long budgetMillis) {
        this.clock=clock;this.retryMillis=retryMillis;this.budgetMillis=budgetMillis;
        workers=new ThreadPoolExecutor(0,2,10,TimeUnit.SECONDS,new SynchronousQueue<>(),
                threads("allin-catalog-refresh"),new ThreadPoolExecutor.AbortPolicy());
        deadlines=new ScheduledThreadPoolExecutor(1,threads("allin-catalog-deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setKeepAliveTime(10,TimeUnit.SECONDS);deadlines.allowCoreThreadTimeOut(true);
    }
    private static ThreadFactory threads(String name) {
        return task -> {Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;};
    }
    static Lookup currentLookup() { return CURRENT.get(); }

    /** Scoped only to metadata jobs; ordinary login, claim and inference HTTP is unchanged. */
    static final class Lookup {
        private final long deadline;
        private HttpURLConnection connection;
        private Thread worker;
        private boolean cancelled;
        Lookup(long millis) {deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);}
        synchronized void start() throws InterruptedIOException {check();worker=Thread.currentThread();}
        synchronized void check() throws InterruptedIOException {
            if(cancelled||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline)
                throw new InterruptedIOException("catalog lookup cancelled or timed out");
        }
        synchronized int timeout(int limit) throws InterruptedIOException {
            check();return (int)Math.max(1,Math.min(limit,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())+1));
        }
        synchronized void attach(HttpURLConnection value) throws InterruptedIOException {check();connection=value;}
        synchronized void detach(HttpURLConnection value) {if(connection==value)connection=null;}
        void cancel() {
            HttpURLConnection value;Thread thread;
            synchronized(this){if(cancelled)return;cancelled=true;value=connection;connection=null;thread=worker;worker=null;}
            if(thread!=null&&thread!=Thread.currentThread())thread.interrupt();
            if(value!=null)try{value.disconnect();}catch(RuntimeException ignored){}
        }
    }

    private static String fingerprint(String jwt,String device,NetworkPolicy.Snapshot network) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(String part:new String[]{jwt,device,network.toJson().toString()}) {
                byte[] bytes=part.getBytes(StandardCharsets.UTF_8);int length=bytes.length;
                hash.update(new byte[]{(byte)(length>>>24),(byte)(length>>>16),(byte)(length>>>8),(byte)length});hash.update(bytes);
            }
            StringBuilder result=new StringBuilder(64);for(byte value:hash.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    boolean request(String jwt,String device,NetworkPolicy.Snapshot network,BooleanSupplier current) {
        if(jwt==null||jwt.trim().isEmpty()||device==null||network==null||current==null)return false;
        final String key=fingerprint(jwt,device,network);final Lookup lookup;
        synchronized(this) {
            Long next=retryAt.get(key);
            if(closed||active.containsKey(key)||active.size()>=2||(next!=null&&clock.now()<next))return false;
            lookup=new Lookup(budgetMillis);active.put(key,lookup);retryAt.put(key,clock.now()+retryMillis);
            trimRetries();
        }
        try {
            workers.execute(() -> {
                ScheduledFuture<?> timeout=null;
                try {
                    lookup.start();CURRENT.set(lookup);
                    timeout=deadlines.schedule(lookup::cancel,budgetMillis,TimeUnit.MILLISECONDS);
                    if(!current.getAsBoolean())return;
                    lookup.check();
                    try(NetworkPolicy.Scope ignored=NetworkPolicy.use(network)) {
                        // Normal summary semantics preserve shared single-flight, rate limits and
                        // cache validity. Never force refresh and never call a claim entry point.
                        ZCodeGiftControl.summary(jwt,device);
                    }
                } catch(InterruptedIOException | RuntimeException stopped) {
                    // Metadata remains unconfirmed. Do not invent zero balance or widen models.
                } finally {
                    if(timeout!=null)timeout.cancel(false);
                    CURRENT.remove();lookup.cancel();
                    synchronized(ZCodeCatalogRefresh.this) {
                        active.remove(key);
                        if(!closed){retryAt.put(key,clock.now()+retryMillis);trimRetries();}
                    }
                }
            });
            return true;
        } catch(RejectedExecutionException fullOrClosed) {
            lookup.cancel();synchronized(this){active.remove(key);retryAt.remove(key);}return false;
        }
    }
    private void trimRetries() {
        while(retryAt.size()>64) {
            String victim=null;for(String key:retryAt.keySet())if(!active.containsKey(key)){victim=key;break;}
            if(victim==null)break;retryAt.remove(victim);
        }
    }
    synchronized int activeTasks() {return active.size();}
    @Override public void close() {
        ArrayList<Lookup> lookups;
        synchronized(this){if(closed)return;closed=true;lookups=new ArrayList<>(active.values());retryAt.clear();}
        workers.shutdownNow();deadlines.shutdownNow();for(Lookup lookup:lookups)lookup.cancel();
    }
}
