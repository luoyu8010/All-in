package com.gaeul.allin;

/** One page-owned test stream; this never cancels independent gateway clients. */
final class BridgeStreamOwner implements AutoCloseable {
    static final class Ticket {
        final String id;final long generation;final LocalBridge.Call call;
        Ticket(String id,long generation,int timeout){this.id=id;this.generation=generation;call=new LocalBridge.Call(timeout);}
    }
    private long generation;
    private Ticket active;
    private boolean closed;
    synchronized Ticket begin(String id,int timeout){
        if(closed||active!=null||id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))return null;
        active=new Ticket(id,++generation,timeout);return active;
    }
    synchronized boolean activeId(String id){return active!=null&&active.id.equals(id);}
    synchronized boolean accepts(Ticket ticket){return !closed&&ticket!=null&&ticket.generation==generation;}
    synchronized void finished(Ticket ticket){if(active==ticket)active=null;}
    void cancel(String id){Ticket old;synchronized(this){if(active==null||!active.id.equals(id))return;old=active;active=null;generation++;}old.call.cancel();}
    void cancelAll(){Ticket old;synchronized(this){old=active;active=null;generation++;}if(old!=null)old.call.cancel();}
    @Override public void close(){synchronized(this){closed=true;}cancelAll();}
}
