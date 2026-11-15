package com.gaeul.allin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.concurrent.TimeUnit;

/** Bounded ordinary responses; never used to aggregate a successful inference SSE stream. */
final class BoundedResponse {
    static final int MAX_BYTES=8*1024*1024;
    private BoundedResponse(){}
    static String utf8(byte[] bytes)throws IOException {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad){throw new IOException("invalid response UTF-8");}
    }
    static String readUtf8(InputStream input,int maxBytes,long timeoutMillis)throws IOException {
        if(input==null)return "";
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,timeoutMillis));
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];
            while(true) {
                check(deadline);
                int want=Math.min(buffer.length,maxBytes-out.size()+1);
                // Do not ask a filtering stream to fill a whole buffer while a peer trickles bytes.
                // Its socket read timeout still bounds a completely idle individual read.
                want=Math.min(want,Math.max(1,in.available()));
                int n=in.read(buffer,0,want);
                check(deadline);if(n<0)break;if(n==0)continue;
                if(n>maxBytes-out.size())throw new IOException("response body too large");
                out.write(buffer,0,n);
            }
            return utf8(out.toByteArray());
        }
    }
    private static void check(long deadline)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("response cancelled");
        if(System.nanoTime()-deadline>=0)throw new java.net.SocketTimeoutException("response deadline exceeded");
    }
}
