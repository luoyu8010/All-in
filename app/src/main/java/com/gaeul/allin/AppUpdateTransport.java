package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Credential-free, bounded HTTPS. No CookieHandler, Authenticator, automatic redirect or retry. */
final class AppUpdateTransport implements AppUpdateCore.Transport {
    static final long TIMEOUT=20000;
    static boolean allowed(String target,boolean redirected){
        try{URI u=new URI(target);if(!"https".equals(u.getScheme())||u.getRawUserInfo()!=null||u.getRawFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;String h=u.getHost(),p=u.getRawPath();if(h==null||p==null)return false;
            if("api.github.com".equals(h))return AppUpdateCore.API.equals(target);
            if("github.com".equals(h))return u.getRawQuery()==null&&p.matches("/xunianooo/All-in/releases/download/v?[0-9.]+(?:-test)?/update\\.json");
            return redirected&&("release-assets.githubusercontent.com".equals(h)||"objects.githubusercontent.com".equals(h))&&p.startsWith("/");
        }catch(Exception e){return false;}
    }
    static byte[] requestBytes(URI uri){String p=uri.getRawPath();if(uri.getRawQuery()!=null)p+="?"+uri.getRawQuery();String accept="api.github.com".equals(uri.getHost())?"application/vnd.github+json":"application/octet-stream";return ("GET "+p+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nUser-Agent: All-In-Android-Update\r\nAccept: "+accept+"\r\nAccept-Encoding: identity\r\nX-GitHub-Api-Version: 2022-11-28\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);}
    private static int remaining(long end)throws IOException{long t=end-System.nanoTime();if(t<=0||Thread.currentThread().isInterrupted())throw new InterruptedIOException("update check ended");return (int)Math.max(1,Math.min(10000,TimeUnit.NANOSECONDS.toMillis(t)));}
    private static final class HeaderCapture extends FilterInputStream {
        final ByteArrayOutputStream head=new ByteArrayOutputStream();boolean done;int a=-1,b=-1,c=-1;
        HeaderCapture(InputStream in){super(in);}
        @Override public int read()throws IOException{int n=in.read();if(!done&&n>=0){if(head.size()>=32768)throw new IOException("headers too large");head.write(n);if(a==13&&b==10&&c==13&&n==10)done=true;a=b;b=c;c=n;}return n;}
        @Override public int read(byte[] bytes,int off,int len)throws IOException{return done?in.read(bytes,off,len):super.read(bytes,off,len);}
        Map<String,String> selected(){Map<String,String> out=new HashMap<>();for(String s:new String(head.toByteArray(),StandardCharsets.ISO_8859_1).split("\r\n")){int i=s.indexOf(':');if(i<=0)continue;String key=s.substring(0,i).toLowerCase(Locale.ROOT);if(Arrays.asList("location","retry-after","x-ratelimit-reset").contains(key))out.put(key,s.substring(i+1).trim());}return out;}
    }
    static long retryMillis(Map<String,String> headers,long now){try{String retry=headers.get("retry-after");if(retry!=null&&retry.matches("[0-9]{1,12}"))return Math.min(AppUpdateCore.MAX_BACKOFF,Long.parseLong(retry)*1000L);String reset=headers.get("x-ratelimit-reset");if(reset!=null&&reset.matches("[0-9]{1,12}"))return Math.max(0,Math.min(AppUpdateCore.MAX_BACKOFF,Long.parseLong(reset)*1000L-now));}catch(Exception ignored){}return AppUpdateCore.ERROR_BACKOFF;}
    @Override public AppUpdateCore.Reply get(String target,int limit)throws Exception {
        final long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(TIMEOUT);NetworkPolicy.Snapshot network=NetworkPolicy.capture();
        for(int hop=0;hop<4;hop++){
            if(!allowed(target,hop>0))throw new IOException("untrusted update URL");URI uri=new URI(target);Socket plain=null;SSLSocket secure=null;
            try{
                plain=network.connectTunnel(uri.getHost(),443,remaining(end),remaining(end));secure=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,uri.getHost(),443,true);SSLParameters p=secure.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(p);if(!"HTTPS".equals(secure.getSSLParameters().getEndpointIdentificationAlgorithm()))throw new SSLException("hostname verification unavailable");secure.setSoTimeout(remaining(end));secure.startHandshake();secure.getOutputStream().write(requestBytes(uri));secure.getOutputStream().flush();final SSLSocket socket=secure;
                InputStream deadline=new FilterInputStream(socket.getInputStream()){@Override public int read()throws IOException{socket.setSoTimeout(remaining(end));return in.read();}@Override public int read(byte[] b,int o,int l)throws IOException{if(l==0)return 0;socket.setSoTimeout(remaining(end));return in.read(b,o,Math.min(l,Math.max(1,in.available())));}};
                HeaderCapture in=new HeaderCapture(deadline);QoderAuth.Head h=QoderAuth.readHead(in);Map<String,String> headers=in.selected();
                if(h.status==301||h.status==302||h.status==303||h.status==307||h.status==308){if(AppUpdateCore.API.equals(target))throw new IOException("unexpected API redirect");String next=headers.get("location");if(next==null||!allowed(next,true))throw new IOException("untrusted redirect");target=next;continue;}
                byte[] body=QoderAuth.readBody(in,h,limit);remaining(end);return new AppUpdateCore.Reply(h.status,BoundedResponse.utf8(body),retryMillis(headers,System.currentTimeMillis()));
            }finally{if(secure!=null)try{secure.close();}catch(IOException ignored){}else if(plain!=null)try{plain.close();}catch(IOException ignored){}}
        }
        throw new IOException("too many update redirects");
    }
}
