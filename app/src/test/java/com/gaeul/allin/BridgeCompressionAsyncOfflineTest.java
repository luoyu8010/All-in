package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;

/** Production stream/request functions, real loopback sockets, synthetic payloads only. */
public final class BridgeCompressionAsyncOfflineTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void await(CountDownLatch latch)throws Exception{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}
    static byte[] gzip(byte[] data)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream z=new GZIPOutputStream(out)){z.write(data);}return out.toByteArray();}
    static final byte[] TEXT="data: {\"text\":\"中文\"}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
    static byte[] chunk(byte[] data,String tail)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();out.write((Integer.toHexString(data.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.write(("\r\n"+tail).getBytes(StandardCharsets.US_ASCII));return out.toByteArray();}
    static void compressedWire(String kind)throws Exception {
        byte[] compressed=gzip(TEXT);boolean fixed=kind.startsWith("fixed");boolean valid=kind.endsWith("valid");
        byte[] body=fixed?compressed:chunk(compressed,kind.equals("chunk-valid")?"0\r\nX-Test: yes\r\n\r\n":kind.equals("chunk-trailer")?"0\r\nX-Test: incomplete\r\n":"");
        long length=compressed.length+(kind.equals("fixed-short")?10:0);CountDownLatch release=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicInteger ends=new AtomicInteger();StringBuilder received=new StringBuilder();
        try(BridgeTransportLifecycleOfflineTest.Peer p=new BridgeTransportLifecycleOfflineTest.Peer(s->{OutputStream out=s.getOutputStream();BridgeTransportLifecycleOfflineTest.write(out,"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n"+(fixed?"Content-Length: "+length:"Transfer-Encoding: chunked")+"\r\nConnection: keep-alive\r\n\r\n");out.write(body);out.flush();if(valid)await(release);})) {
            try {LocalBridge.stream("POST","/v1/chat/completions","{}",1500,new LocalBridge.StreamCallback(){public boolean onLine(String line){received.append(line).append('\n');return true;}public void onDone(int status,String why){ends.incrementAndGet();error.set(why);check(status==200,"wire status preserved for diagnosis");}});}finally{release.countDown();}
            check(ends.get()==1,"one terminal callback");check(error.get()!=null && (valid?error.get().isEmpty():!error.get().isEmpty()),"HTTP gzip integrity: "+kind+" must "+(valid?"succeed":"reject"));
            if(valid)check(received.toString().contains("中文")&&received.toString().contains("[DONE]"),"valid gzip text remains exact");p.complete();check(p.sends.get()==1,"gzip test never replays");
        }
    }
    static void fragmentedGzip()throws Exception {
        CountDownLatch first=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicInteger lines=new AtomicInteger();
        try(BridgeTransportLifecycleOfflineTest.Peer p=new BridgeTransportLifecycleOfflineTest.Peer(s->{OutputStream out=s.getOutputStream();BridgeTransportLifecycleOfflineTest.write(out,"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n\r\n");OutputStream chunks=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}public void write(byte[] b,int o,int n)throws IOException{if(n>0)BridgeTransportLifecycleOfflineTest.chunk(out,Arrays.copyOfRange(b,o,o+n));}public void close()throws IOException{out.flush();}};GZIPOutputStream z=new GZIPOutputStream(chunks,true);z.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));z.flush();await(first);z.write(TEXT);z.close();BridgeTransportLifecycleOfflineTest.write(out,"0\r\n\r\n");})) {
            LocalBridge.stream("POST","/v1/chat/completions","{}",3000,new LocalBridge.StreamCallback(){public boolean onLine(String line){if(line.equals("data: first"))first.countDown();lines.incrementAndGet();return true;}public void onDone(int status,String why){error.set(why);}});
            check(error.get()!=null&&error.get().isEmpty(),"fragmented gzip framing completes");check(first.getCount()==0&&lines.get()>=4,"first decoded frame arrives before upstream completion");p.complete();
        }
    }
    static void decoderControls()throws Exception {
        Method m=LocalBridge.class.getDeclaredMethod("streamBody",InputStream.class,QoderAuth.Head.class,String.class);m.setAccessible(true);
        byte[] compressed=gzip(TEXT);for(boolean encoded:new boolean[]{false,true})for(boolean fixed:new boolean[]{false,true}) {
            byte[] bytes=encoded?compressed:TEXT;byte[] body=fixed?bytes:chunk(bytes,"");QoderAuth.Head h=new QoderAuth.Head(200,fixed?bytes.length+10:-1,!fixed,encoded?"gzip":null);boolean rejected=false;
            try(InputStream in=(InputStream)ConfigConsistencyOfflineTest.invoke(m,null,new ByteArrayInputStream(body),h,"POST")){in.readAllBytes();}catch(IOException expected){rejected=true;}
            check(rejected,"both identity and gzip reject "+(fixed?"short fixed body":"missing chunk terminator"));
        }
    }
    static void longCompressedStream()throws Exception {
        AtomicLong total=new AtomicLong();AtomicReference<String> error=new AtomicReference<>();CountDownLatch first=new CountDownLatch(1);
        try(BridgeTransportLifecycleOfflineTest.Peer p=new BridgeTransportLifecycleOfflineTest.Peer(s->{OutputStream out=s.getOutputStream();BridgeTransportLifecycleOfflineTest.write(out,"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n\r\n");OutputStream chunks=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}public void write(byte[] b,int o,int n)throws IOException{if(n>0)BridgeTransportLifecycleOfflineTest.chunk(out,Arrays.copyOfRange(b,o,o+n));}public void close()throws IOException{out.flush();}};GZIPOutputStream z=new GZIPOutputStream(chunks,true);z.write("data: first\n".getBytes(StandardCharsets.US_ASCII));z.flush();await(first);byte[] frame=new byte[18432];Arrays.fill(frame,(byte)'x');frame[frame.length-1]='\n';for(int i=0;i<520;i++){z.write(frame);z.flush();}z.close();BridgeTransportLifecycleOfflineTest.write(out,"0\r\n\r\n");})) {
            LocalBridge.stream("POST","/v1/chat/completions","{}",15000,new LocalBridge.StreamCallback(){public boolean onLine(String line){first.countDown();total.addAndGet(line.length());return true;}public void onDone(int status,String why){error.set(why);}});
            check(first.getCount()==0&&total.get()>BoundedResponse.MAX_BYTES,"Valid decoded gzip SSE exceeds 8 MiB without aggregation or output truncation");check(error.get()!=null&&error.get().isEmpty(),"Long gzip stream completes after HTTP framing");p.complete();check(p.sends.get()==1,"Long gzip has exactly one send");
        }
    }
    static Object activity(Object bridge)throws Exception{Field parent=bridge.getClass().getDeclaredField("this$0");parent.setAccessible(true);return parent.get(bridge);}
    static Method request(Object bridge)throws Exception {
        Method m;try{m=bridge.getClass().getDeclaredMethod("doRequest",String.class,String.class,String.class,long.class);}catch(NoSuchMethodException old){m=bridge.getClass().getDeclaredMethod("doRequest",String.class,String.class,String.class);}m.setAccessible(true);return m;
    }
    static void asynchronousStart(String mode)throws Exception {
        Object bridge=AdvisoryBoundaryOfflineTest.nativeBridge(),owner=activity(bridge);Field generation=MainActivity.class.getDeclaredField("bridgePageGeneration");generation.setAccessible(true);Field destroyed=MainActivity.class.getDeclaredField("bridgeDestroyed");destroyed.setAccessible(true);Method cancel=MainActivity.class.getDeclaredMethod("cancelBridgeCalls");cancel.setAccessible(true);Method m=request(bridge);
        long captured=generation.getLong(owner);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<JSONObject> response=new AtomicReference<>();AtomicInteger sends=new AtomicInteger();CountDownLatch ready=new CountDownLatch(1),go=new CountDownLatch(1);
        try(ServerSocket listener=new ServerSocket()) {
            listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(800);
            Thread peer=new Thread(()->{try(Socket s=listener.accept()){s.setSoTimeout(3000);BridgeTransportLifecycleOfflineTest.readRequest(s.getInputStream());sends.incrementAndGet();BridgeTransportLifecycleOfflineTest.write(s.getOutputStream(),"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");}catch(SocketTimeoutException noConnection){}catch(Throwable t){failure.set(t);}},"async-count-peer");peer.start();
            Thread worker=new Thread(()->{try{ready.countDown();await(go);Object value=m.getParameterCount()==4?ConfigConsistencyOfflineTest.invoke(m,bridge,"POST","/api/privacy","{}",captured):ConfigConsistencyOfflineTest.invoke(m,bridge,"POST","/api/privacy","{}");response.set(new JSONObject((String)value));}catch(Throwable t){failure.set(t);}},"delayed-bridge-request");worker.start();await(ready);
            if(!mode.equals("fresh")){synchronized(owner){generation.setLong(owner,captured+1);ConfigConsistencyOfflineTest.invoke(cancel,owner);if(mode.equals("destroy"))destroyed.setBoolean(owner,true);}}
            go.countDown();ConfigConsistencyOfflineTest.join(worker);ConfigConsistencyOfflineTest.join(peer);if(failure.get()!=null)throw new AssertionError(failure.get());
            check(sends.get()==(mode.equals("fresh")?1:0),mode+" async start actual requests="+sends.get());check(response.get()!=null&&response.get().getInt("status")== (mode.equals("fresh")?200:0),"old page cannot send a late ordinary request; fresh page remains usable");
        }
    }
    public static void main(String[] args)throws Exception {
        TestPrivacyScope.initialize();String group=args.length==0?"all":args[0];int failures=0;
        if(group.equals("all")||group.equals("gzip")){for(String kind:new String[]{"fixed-valid","chunk-valid","fixed-short","chunk-missing","chunk-trailer"})try{compressedWire(kind);}catch(Throwable t){failures++;System.err.println("FAIL "+kind+": "+t);}try{decoderControls();fragmentedGzip();longCompressedStream();}catch(Throwable t){failures++;System.err.println("FAIL gzip controls: "+t);}}
        if(group.equals("all")||group.equals("async"))for(String mode:new String[]{"fresh","page","destroy"})try{asynchronousStart(mode);}catch(Throwable t){failures++;System.err.println("FAIL async "+mode+": "+t);}
        if(failures>0)throw new AssertionError(failures+" scenarios failed; "+checks+" checks");System.out.println("BridgeCompressionAsyncOfflineTest PASS: "+checks+" checks");
    }
}
