package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Fixed loopback transport. Ordinary bodies are bounded; SSE stays incremental and cancellable. */
final class LocalBridge {
    static final String HOST="127.0.0.1";
    static final int PORT=3425;
    static final int MAX_RESPONSE_BYTES=BoundedResponse.MAX_BYTES;
    private LocalBridge(){}

    /** A call owns its socket before connect, so cancellation also covers startup races. */
    static final class Call implements Closeable {
        private final long deadline;
        private Socket socket;
        private volatile boolean cancelled,closed;
        Call(long timeoutMillis){deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,timeoutMillis));}
        synchronized void attach(Socket value)throws IOException {
            if(closed||cancelled){value.close();throw new IOException("bridge cancelled");}socket=value;
        }
        int remaining()throws IOException {
            if(cancelled||closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("bridge cancelled");
            long left=deadline-System.nanoTime();if(left<=0)throw new SocketTimeoutException("bridge deadline");
            return (int)Math.max(1,Math.min(Integer.MAX_VALUE,TimeUnit.NANOSECONDS.toMillis(left)+1));
        }
        boolean cancelled(){return cancelled;}
        void cancel(){cancelled=true;close();}
        @Override public void close(){Socket active;synchronized(this){closed=true;active=socket;socket=null;}closeQuietly(active);}
    }
    static boolean isGatewayUp(){try(Socket s=new Socket()){s.connect(new InetSocketAddress(HOST,PORT),400);return true;}catch(Exception e){return false;}}
    static String get(String pathAndQuery){return request("GET",pathAndQuery,null,15000);}
    static String post(String pathAndQuery,String jsonBody){return request("POST",pathAndQuery,jsonBody,20000);}

    static final class Result {
        final int status;final String body,transportError;
        Result(int status,String body,String error){this.status=status;this.body=body;this.transportError=error;}
        static Result failure(String kind){return new Result(0,"",kind);}
        boolean accountSaved(){
            if(status<200||status>=300||!transportError.isEmpty())return false;
            try{
                com.gaeul.allin.json.JSONTokener parser=new com.gaeul.allin.json.JSONTokener(body);
                Object parsed=parser.nextValue();
                if(!(parsed instanceof com.gaeul.allin.json.JSONObject)||parser.nextClean()!=0)return false;
                com.gaeul.allin.json.JSONObject value=(com.gaeul.allin.json.JSONObject)parsed;
                Object id=value.opt("id");
                if(!Boolean.TRUE.equals(value.opt("ok"))||!(id instanceof String)||((String)id).trim().isEmpty()||((String)id).length()>512)return false;
                for(char c:((String)id).toCharArray())if(c<32||c==127)return false;
                return true;
            }catch(Exception bad){return false;}
        }
        String accountFailureMessage(){
            if(!transportError.isEmpty())return "未收到完整保存确认，请检查网关连接后重试";
            if(status==401||status==403)return "本地管理身份校验失败，请返回首页后重新登录";
            if(status==409)return "账号已存在或正在处理，请检查账号池后重试";
            if(status==400||status==422)return "凭据无效或信息不完整，请重新完成官方授权";
            if(status>=500)return "网关保存失败，账号尚未确认入池，请重试";
            return "未获得有效入池确认，请检查账号池后重试";
        }
    }
    static Result postResult(String path,String body){return requestResult("POST",path,body,20000);}
    static String request(String method,String path,String body,int timeoutMs){Result r=requestResult(method,path,body,timeoutMs);return r.transportError.isEmpty()?r.body:null;}
    static Result requestResult(String method,String path,String body,int timeoutMs){return requestResult(method,path,body,new Call(timeoutMs));}
    static Result requestResult(String method,String path,String body,Call call){
        try{
            BridgeRequestValidator.Request request=validated(method,path);
            Socket socket=send(request,body,false,call);InputStream in=deadlineInput(socket,call);
            QoderAuth.Head head=QoderAuth.readHead(in);validateStatus(head);
            byte[] bytes="HEAD".equals(request.method)||head.status==204||head.status==304?new byte[0]:QoderAuth.readBody(in,head,MAX_RESPONSE_BYTES);
            call.remaining();return new Result(head.status,BoundedResponse.utf8(bytes),"");
        }catch(Exception failure){return Result.failure(errorKind(failure,call));}
        finally{call.close();}
    }
    private static BridgeRequestValidator.Request validated(String method,String path){
        String m=method==null||method.isEmpty()?"GET":method.toUpperCase(Locale.ROOT);
        String p=path==null||path.isEmpty()?"/":path;if(p.charAt(0)!='/')p="/"+p;
        return BridgeRequestValidator.validate(m,p);
    }
    private static Socket send(BridgeRequestValidator.Request request,String body,boolean stream,Call call)throws IOException {
        byte[] payload=body==null?new byte[0]:body.getBytes("UTF-8");
        Socket socket=new Socket();call.attach(socket);socket.connect(new InetSocketAddress(HOST,PORT),Math.min(4000,call.remaining()));socket.setSoTimeout(call.remaining());
        StringBuilder head=new StringBuilder(request.method).append(' ').append(request.pathAndQuery).append(" HTTP/1.1\r\n")
                .append("Host: ").append(HOST).append(':').append(PORT).append("\r\nOrigin: null\r\nConnection: close\r\nX-All-In-Bridge: 1\r\nAccept-Encoding: identity\r\n");
        if(stream)head.append("Accept: text/event-stream\r\n");
        String management=GatewayManagementCredential.get();if(!management.isEmpty())head.append("X-All-In-Management: ").append(management).append("\r\n");
        String key=GatewayAuthKeyHolder.get();if(key!=null&&!key.isEmpty())head.append("Authorization: Bearer ").append(key).append("\r\n");
        if(payload.length>0)head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        call.remaining();OutputStream out=socket.getOutputStream();out.write(head.toString().getBytes("UTF-8"));if(payload.length>0)out.write(payload);out.flush();call.remaining();return socket;
    }
    private static InputStream deadlineInput(final Socket socket,final Call call)throws IOException {
        return new FilterInputStream(socket.getInputStream()){
            @Override public int read()throws IOException{socket.setSoTimeout(call.remaining());int n=in.read();call.remaining();return n;}
            @Override public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;socket.setSoTimeout(call.remaining());int n=in.read(b,off,len);call.remaining();return n;}
        };
    }
    private static void validateStatus(QoderAuth.Head head)throws IOException{if(head.status<200||head.status>599)throw new IOException("invalid HTTP status");}
    private static String errorKind(Exception error,Call call){return call.cancelled()?"cancelled":error instanceof SocketTimeoutException?"timeout":error.getClass().getSimpleName();}

    interface StreamCallback {boolean onLine(String line);void onDone(int status,String error);}
    static void stream(String method,String path,String body,int timeoutMs,StreamCallback cb){stream(method,path,body,new Call(timeoutMs),cb);}
    static void stream(String method,String path,String body,Call call,StreamCallback cb){
        int status=0;String error="";InputStream data=null;
        try{
            BridgeRequestValidator.Request request=validated(method,path);Socket socket=send(request,body,true,call);InputStream raw=deadlineInput(socket,call);
            QoderAuth.Head head=QoderAuth.readHead(raw);validateStatus(head);status=head.status;
            data=streamBody(raw,head,request.method);
            ByteArrayOutputStream line=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            while((n=data.read(buffer))!=-1){
                call.remaining();
                for(int i=0;i<n;i++){
                    byte b=buffer[i];
                    if(b=='\n'){
                        String text=BoundedResponse.utf8(line.toByteArray());line.reset();if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
                        if(!cb.onLine(text)){error="cancelled";return;}
                    }else{if(line.size()>=MAX_RESPONSE_BYTES)throw new IOException("stream line too large");line.write(b);}
                }
            }
            if(line.size()>0&&!cb.onLine(BoundedResponse.utf8(line.toByteArray())))error="cancelled";
            call.remaining();
        }catch(Exception failure){error=errorKind(failure,call);}
        finally{call.close();if(data!=null)try{data.close();}catch(IOException ignored){}try{cb.onDone(status,error);}catch(Exception ignored){}}
    }
    private static InputStream streamBody(InputStream raw,QoderAuth.Head head,String method)throws IOException {
        if("HEAD".equals(method)||head.status==204||head.status==304)return new ByteArrayInputStream(new byte[0]);
        InputStream framed=head.chunked?new ChunkedIn(raw):head.contentLength>=0?new FixedIn(raw,head.contentLength):raw;
        if(head.encoding==null)return framed;
        if("gzip".equals(head.encoding)||"x-gzip".equals(head.encoding))return new CompleteGzipIn(framed);
        throw new IOException("unsupported content encoding");
    }
    /** Gzip EOS is not HTTP EOS: finish the framing before reporting a complete stream. */
    static final class CompleteGzipIn extends InputStream {
        private final InputStream framed;
        private final java.util.zip.GZIPInputStream gzip;
        private boolean complete;
        CompleteGzipIn(InputStream framed)throws IOException {this.framed=framed;gzip=new java.util.zip.GZIPInputStream(framed);}
        @Override public int read()throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException {
            if(len==0)return 0;if(complete)return -1;
            int n=gzip.read(b,off,len);
            if(n<0){byte[] discard=new byte[2048];while(framed.read(discard)!=-1){}complete=true;}
            return n;
        }
        @Override public void close()throws IOException {gzip.close();}
    }
    /** Fixed length is a boundary, not a hint: never treat premature EOF as a complete response. */
    static final class FixedIn extends InputStream {
        final InputStream in;long left;FixedIn(InputStream in,long length){this.in=in;left=length;}
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(left==0)return -1;int n=in.read(b,off,(int)Math.min(left,len));if(n<0)throw new EOFException("truncated fixed body");left-=n;return n;}
        @Override public void close()throws IOException{in.close();}
    }
    /** Incremental strict chunk framing, without a total SSE size cap or whole-stream buffering. */
    static final class ChunkedIn extends InputStream {
        final InputStream in;long remaining;boolean terminator,done;
        ChunkedIn(InputStream in){this.in=in;}
        private void next()throws IOException {
            if(terminator){if(in.read()!=13||in.read()!=10)throw new IOException("invalid chunk terminator");terminator=false;}
            String line=httpLine(in,1024);int semi=line.indexOf(';');String hex=semi<0?line:line.substring(0,semi);
            if(!hex.matches("[0-9A-Fa-f]{1,15}"))throw new IOException("invalid chunk size");
            remaining=Long.parseLong(hex,16);
            if(remaining==0){int total=0,count=0;while(true){String trailer=httpLine(in,8192);total+=trailer.length()+2;if(total>32768||count++>64)throw new IOException("trailers too large");if(trailer.isEmpty())break;if(!trailer.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+:.*"))throw new IOException("invalid trailer");}done=true;}
        }
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException {
            if(len==0)return 0;if(done)return -1;if(remaining==0)next();if(done)return -1;
            int n=in.read(b,off,(int)Math.min(remaining,len));if(n<0)throw new EOFException("truncated chunk body");remaining-=n;if(remaining==0)terminator=true;return n;
        }
        @Override public void close()throws IOException{in.close();}
    }
    private static String httpLine(InputStream in,int max)throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        while(true){int b=in.read();if(b<0)throw new EOFException("incomplete HTTP line");if(b==13){if(in.read()!=10)throw new IOException("invalid HTTP line ending");return line.toString("ISO-8859-1");}if(b==10||b==127||(b<32&&b!=9)||line.size()>=max)throw new IOException("invalid HTTP line");line.write(b);}
    }
    private static void closeQuietly(Socket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
}
