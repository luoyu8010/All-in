package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real sockets, a 64 MiB test heap, synthetic bodies only. No Android account or upstream access. */
public final class BridgeTransportLifecycleOfflineTest {
    static int checks;
    static final AtomicLong peakUsed=new AtomicLong();
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void await(CountDownLatch l)throws Exception{if(!l.await(5,TimeUnit.SECONDS))throw new AssertionError("peer timeout");}
    interface Action{void run(Socket s)throws Exception;}
    static final class Peer implements AutoCloseable {
        final ServerSocket listener=new ServerSocket();final AtomicInteger sends=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();final Thread worker;volatile Socket accepted;
        Peer(Action action)throws Exception {
            listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(7000);
            worker=new Thread(()->{try(Socket s=listener.accept()){accepted=s;s.setSoTimeout(5000);readRequest(s.getInputStream());sends.incrementAndGet();action.run(s);}catch(SocketException closed){if(!listener.isClosed())failure.set(closed);}catch(Throwable e){failure.set(e);}},"bridge-fixture-peer");worker.start();
        }
        void complete()throws Exception{worker.join(7000);check(!worker.isAlive(),"peer exited");if(failure.get()!=null)throw new AssertionError("peer failed",failure.get());}
        public void close()throws Exception{listener.close();if(accepted!=null)accepted.close();worker.join(7000);if(worker.isAlive())throw new AssertionError("fixture leaked a peer");}
    }
    static void readRequest(InputStream in)throws IOException {
        ByteArrayOutputStream h=new ByteArrayOutputStream();int a=-1,b=-1,c=-1,x;while((x=in.read())!=-1){h.write(x);if(h.size()>32768)throw new IOException("fixture request too large");if(a==13&&b==10&&c==13&&x==10)break;a=b;b=c;c=x;}
        int length=0;for(String row:h.toString("ISO-8859-1").split("\r\n"))if(row.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(row.substring(15).trim());
        while(length-->0)if(in.read()<0)throw new EOFException();
    }
    static void write(OutputStream out,String s)throws IOException{out.write(s.getBytes(StandardCharsets.US_ASCII));out.flush();}
    static void chunk(OutputStream out,byte[] bytes)throws IOException{write(out,Integer.toHexString(bytes.length)+";fixture=yes\r\n");out.write(bytes);write(out,"\r\n");}
    static void framedWithoutEof(boolean chunked)throws Exception {
        CountDownLatch release=new CountDownLatch(1);byte[] bytes="{\"text\":\"正常中文\"}".getBytes(StandardCharsets.UTF_8);
        try(Peer p=new Peer(s->{OutputStream out=s.getOutputStream();write(out,"HTTP/1.1 200 OK\r\n"+(chunked?"Transfer-Encoding: chunked":"Content-Length: "+bytes.length)+"\r\nConnection: keep-alive\r\n\r\n");if(chunked){chunk(out,bytes);write(out,"0\r\nX-Test: yes\r\n\r\n");}else{out.write(bytes);out.flush();}await(release);})) {
            LocalBridge.Result r;try{r=LocalBridge.requestResult("GET","/api/health",null,1200);}finally{release.countDown();}
            check(r.status==200&&r.transportError.isEmpty(),"complete "+(chunked?"chunked":"fixed")+" response does not wait for TCP EOF");check(r.body.contains("正常中文"),"UTF-8 body exact");p.complete();check(p.sends.get()==1,"one wire request");
        }
    }
    static void slowDeadline()throws Exception {
        CountDownLatch closed=new CountDownLatch(1);
        try(Peer p=new Peer(s->{write(s.getOutputStream(),"HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n");try{for(int i=0;i<200;i++){s.getOutputStream().write('x');s.getOutputStream().flush();java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(15));}}catch(IOException expected){closed.countDown();}})) {
            long start=System.nanoTime();LocalBridge.Result r=LocalBridge.requestResult("GET","/api/health",null,250);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            check(r.status==0&&"timeout".equals(r.transportError),"slow trickle cannot reset overall deadline");check(elapsed<1500,"deadline bounded despite continuous bytes");await(closed);p.complete();check(p.sends.get()==1,"timeout never replays request");
        }
    }
    static void cancelOwnedStream(String mode)throws Exception {
        BridgeStreamOwner owner=new BridgeStreamOwner();BridgeStreamOwner.Ticket ticket=owner.begin("first",5000);check(ticket!=null,"owner admits first stream");check(owner.begin("second",5000)==null,"owner refuses duplicate before any second send");
        CountDownLatch line=new CountDownLatch(1),peerClosed=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicInteger completed=new AtomicInteger();
        try(Peer p=new Peer(s->{write(s.getOutputStream(),"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");chunk(s.getOutputStream(),"data: first\n".getBytes(StandardCharsets.UTF_8));if(s.getInputStream().read()==-1)peerClosed.countDown();else throw new AssertionError("unexpected bytes instead of cancellation");})) {
            Thread stream=new Thread(()->{try{LocalBridge.stream("POST","/v1/chat/completions","{}",ticket.call,new LocalBridge.StreamCallback(){public boolean onLine(String text){line.countDown();return true;}public void onDone(int status,String reason){completed.incrementAndGet();error.set(reason);}});}finally{owner.finished(ticket);}},"owned-stream");stream.start();await(line);
            owner.cancel("unrelated-request");check(owner.accepts(ticket),"cannot cancel another request by a wrong ID");
            long start=System.nanoTime();if("manual".equals(mode))owner.cancel("first");else if("page".equals(mode))owner.cancelAll();else owner.close();
            stream.join(1500);check(!stream.isAlive(),mode+" cancellation wakes blocked socket read");await(peerClosed);check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000,"peer actually observes connection close");
            check(p.sends.get()==1&&completed.get()==1,"one upstream send and one terminal callback");check("cancelled".equals(error.get()),"cancellation is not reported as successful EOF");check(!owner.accepts(ticket),"queued old callbacks rejected");p.complete();
            if(!"destroy".equals(mode)){BridgeStreamOwner.Ticket next=owner.begin("next",1000);check(next!=null,"new page request allowed after cancel");owner.finished(ticket);check(owner.activeId("next")&&owner.accepts(next),"old completion cannot release newer request");}
        }finally{owner.close();}
    }
    static void cancelBeforeConnect()throws Exception {
        LocalBridge.Call c=new LocalBridge.Call(5000);c.cancel();
        try(ServerSocket listener=new ServerSocket()){listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(150);LocalBridge.Result r=LocalBridge.requestResult("GET","/api/health",null,c);check(r.status==0&&"cancelled".equals(r.transportError),"pre-connect cancellation reported");try(Socket unexpected=listener.accept()){throw new AssertionError("cancelled request connected");}catch(SocketTimeoutException expected){check(true,"pre-connect cancel sent zero requests");}}
    }
    static void longStream()throws Exception {
        final int frames=520,size=18432;CountDownLatch first=new CountDownLatch(1);AtomicLong received=new AtomicLong();AtomicInteger lines=new AtomicInteger();AtomicReference<String> error=new AtomicReference<>();
        try(Peer p=new Peer(s->{OutputStream out=s.getOutputStream();write(out,"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");chunk(out,"data: first\n".getBytes(StandardCharsets.UTF_8));await(first);byte[] frame=new byte[size];Arrays.fill(frame,(byte)'x');frame[frame.length-1]='\n';for(int i=0;i<frames;i++)chunk(out,frame);write(out,"0\r\n\r\n");})) {
            LocalBridge.stream("POST","/v1/chat/completions","{}",15000,new LocalBridge.StreamCallback(){public boolean onLine(String text){first.countDown();lines.incrementAndGet();received.addAndGet(text.length());long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();peakUsed.accumulateAndGet(used,Math::max);return true;}public void onDone(int status,String reason){error.set(reason);check(status==200,"long stream HTTP status retained");}});
            check(error.get()!=null&&error.get().isEmpty(),"valid long stream finishes without error");check(received.get()>BoundedResponse.MAX_BYTES,"SSE total exceeds ordinary-response cap without being aggregated or rejected");check(lines.get()==frames+1,"every incremental line retained");p.complete();check(p.sends.get()==1,"long stream sent once");
        }
    }
    static void malformedChunks()throws Exception {
        for(String body:new String[]{"2\r\nx","2\r\n{}xx0\r\n\r\n","-1\r\n","aZ\r\n","0\r\nX: unfinished\r\n","0\n\n","2\r\n{}\r\n"}){
            boolean rejected=false;try(InputStream in=new LocalBridge.ChunkedIn(new ByteArrayInputStream(body.getBytes(StandardCharsets.US_ASCII)))){in.readAllBytes();}catch(IOException expected){rejected=true;}check(rejected,"malformed chunk is not successful EOF");
        }
    }
    static void boundedSlowRead()throws Exception {
        AtomicBoolean closed=new AtomicBoolean();InputStream slow=new InputStream(){public int read(){java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));return 'x';}public void close(){closed.set(true);}};
        boolean timed=false;try{BoundedResponse.readUtf8(slow,1024,20);}catch(SocketTimeoutException expected){timed=true;}
        check(timed&&closed.get(),"ordinary control read has a total deadline and closes on failure");
    }
    public static void main(String[] args)throws Exception {
        TestPrivacyScope.initialize();framedWithoutEof(false);framedWithoutEof(true);slowDeadline();cancelBeforeConnect();for(String mode:new String[]{"manual","page","destroy"})cancelOwnedStream(mode);longStream();malformedChunks();boundedSlowRead();
        System.out.println("BridgeTransportLifecycleOfflineTest PASS: "+checks+" checks; heapLimit="+Runtime.getRuntime().maxMemory()+"; sampledStreamPeak="+peakUsed.get());
    }
}
