package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;

/** An accepted socket's real peer bytes with a numeric display name. No PTR or privilege change. */
final class NumericPeerSocket extends Socket {
    private final Socket delegate;private final InetAddress peer;
    NumericPeerSocket(Socket socket)throws IOException {
        delegate=socket;InetAddress actual=socket.getInetAddress();
        if(actual==null)throw new IOException("Accepted socket has no peer");
        peer=InetAddress.getByAddress(actual.getHostAddress(),actual.getAddress());
    }
    @Override public InetAddress getInetAddress(){return peer;}
    @Override public SocketAddress getRemoteSocketAddress(){return new InetSocketAddress(peer,delegate.getPort());}
    @Override public InetAddress getLocalAddress(){return delegate.getLocalAddress();}
    @Override public SocketAddress getLocalSocketAddress(){return delegate.getLocalSocketAddress();}
    @Override public int getPort(){return delegate.getPort();}
    @Override public int getLocalPort(){return delegate.getLocalPort();}
    @Override public InputStream getInputStream()throws IOException{return delegate.getInputStream();}
    @Override public OutputStream getOutputStream()throws IOException{return delegate.getOutputStream();}
    @Override public void close()throws IOException{delegate.close();}
    @Override public void shutdownInput()throws IOException{delegate.shutdownInput();}
    @Override public void shutdownOutput()throws IOException{delegate.shutdownOutput();}
    @Override public boolean isClosed(){return delegate.isClosed();}
    @Override public boolean isConnected(){return delegate.isConnected();}
    @Override public boolean isBound(){return delegate.isBound();}
    @Override public boolean isInputShutdown(){return delegate.isInputShutdown();}
    @Override public boolean isOutputShutdown(){return delegate.isOutputShutdown();}
    @Override public SocketChannel getChannel(){return delegate.getChannel();}
    @Override public void setSoTimeout(int n)throws SocketException{delegate.setSoTimeout(n);}
    @Override public int getSoTimeout()throws SocketException{return delegate.getSoTimeout();}
    @Override public void setTcpNoDelay(boolean b)throws SocketException{delegate.setTcpNoDelay(b);}
    @Override public boolean getTcpNoDelay()throws SocketException{return delegate.getTcpNoDelay();}
    @Override public void setKeepAlive(boolean b)throws SocketException{delegate.setKeepAlive(b);}
    @Override public boolean getKeepAlive()throws SocketException{return delegate.getKeepAlive();}
    @Override public void setSendBufferSize(int n)throws SocketException{delegate.setSendBufferSize(n);}
    @Override public int getSendBufferSize()throws SocketException{return delegate.getSendBufferSize();}
    @Override public void setReceiveBufferSize(int n)throws SocketException{delegate.setReceiveBufferSize(n);}
    @Override public int getReceiveBufferSize()throws SocketException{return delegate.getReceiveBufferSize();}
    @Override public void setSoLinger(boolean b,int n)throws SocketException{delegate.setSoLinger(b,n);}
    @Override public int getSoLinger()throws SocketException{return delegate.getSoLinger();}
    @Override public void setReuseAddress(boolean b)throws SocketException{delegate.setReuseAddress(b);}
    @Override public boolean getReuseAddress()throws SocketException{return delegate.getReuseAddress();}
    @Override public void setOOBInline(boolean b)throws SocketException{delegate.setOOBInline(b);}
    @Override public boolean getOOBInline()throws SocketException{return delegate.getOOBInline();}
    @Override public void setTrafficClass(int n)throws SocketException{delegate.setTrafficClass(n);}
    @Override public int getTrafficClass()throws SocketException{return delegate.getTrafficClass();}
    @Override public void sendUrgentData(int n)throws IOException{delegate.sendUrgentData(n);}
    @Override public void connect(SocketAddress a)throws IOException{delegate.connect(a);}
    @Override public void connect(SocketAddress a,int n)throws IOException{delegate.connect(a,n);}
    @Override public void bind(SocketAddress a)throws IOException{delegate.bind(a);}
}
