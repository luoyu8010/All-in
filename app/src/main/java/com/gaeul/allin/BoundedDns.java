package com.gaeul.allin;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounds otherwise synchronous platform DNS without an unbounded task/thread queue.
 * A resolver that ignores interruption continues occupying its original slot, never a replacement
 * thread. Saturation rejects instead of spawning more work; numeric literals need no DNS slot.
 */
final class BoundedDns {
    interface Resolver { InetAddress[] resolve(String host)throws UnknownHostException; }
    static volatile Resolver resolver=InetAddress::getAllByName;
    private static final AtomicInteger IDS=new AtomicInteger();
    private static final ThreadPoolExecutor POOL=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"allin-outbound-dns-"+IDS.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static int active(){return POOL.getActiveCount();}
    static InetAddress[] resolve(String host,long deadline)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Outbound DNS cancelled");
        if(host.indexOf(':')>=0||host.matches("[0-9]+(?:\\.[0-9]+){3}"))return new InetAddress[]{InetAddress.getByName(host)};
        final Resolver current=resolver;Future<InetAddress[]> task;
        try{task=POOL.submit(()->current.resolve(host));}catch(RejectedExecutionException full){throw new IOException("Outbound DNS concurrency limit reached");}
        try {
            long left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException();
            InetAddress[] addresses=task.get(left,TimeUnit.NANOSECONDS);
            if(addresses==null||addresses.length==0)throw new UnknownHostException("No outbound DNS addresses");
            return addresses;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Outbound DNS cancelled");}
        catch(TimeoutException e){throw new SocketTimeoutException("Outbound DNS/connect deadline exceeded");}
        catch(ExecutionException e){if(e.getCause() instanceof IOException)throw (IOException)e.getCause();throw new IOException("Outbound DNS failed");}
        finally{task.cancel(true);}
    }
    static int remainingMillis(long deadline)throws SocketTimeoutException {
        long left=deadline-System.nanoTime();if(left<=0)throw new SocketTimeoutException("Outbound DNS/connect deadline exceeded");
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
    }
}
