package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Synthetic in-memory HTTP transport; never contacts Qoder. */
public final class QoderCatalogIntegrityHttpTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Fake extends HttpURLConnection {
        final int status; final String response; final ByteArrayOutputStream sent = new ByteArrayOutputStream(); boolean disconnected;
        Fake(String url, int status, String response) throws Exception { super(new URL(url)); this.status=status; this.response=response; }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public int getResponseCode() { return status; }
        public OutputStream getOutputStream() { return sent; }
        public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
    }
    static class Transport implements QoderAuth.ConnectionFactory {
        final int status; final String body; Fake last;
        Transport(int status, String body) { this.status=status; this.body=body; }
        public HttpURLConnection open(String url) throws Exception { return last = new Fake(url,status,body); }
    }
    static JSONObject entry(String key, boolean enable, double price) throws Exception {
        return new JSONObject().put("key", key).put("enable", enable).put("price_factor",price).put("display_name", "name-"+key).put("source","system");
    }
    static String listing(boolean paidEnabled) throws Exception {
        JSONArray app=new JSONArray().put(entry("auto",false,1));
        for(int i=0;i<12;i++) app.put(entry("synthetic"+i, i<2 || paidEnabled, i<2 ? 0 : 1));
        return new JSONObject().put("app",app).put("chat",new JSONArray().put(entry("synthetic0",true,5))).toString();
    }
    /** Fixed local plugin index.mjs:240-278: initial job milliseconds, device seconds. */
    static void initialExpiryContract() throws Exception {
        java.lang.reflect.Method initial = QoderAuth.class.getDeclaredMethod("expiresAt", JSONObject.class);
        initial.setAccessible(true);
        long before = System.currentTimeMillis();
        long end = (Long) initial.invoke(null, new JSONObject().put("expires_in", 3600000));
        check(end >= before + 3600000 && end <= System.currentTimeMillis() + 3600000,
                "initial job expiry must be one hour in milliseconds, not multiplied by 1000");
        long epoch = System.currentTimeMillis() + 7200000;
        String iso = java.time.Instant.ofEpochMilli(epoch).toString();
        end = (Long) initial.invoke(null, new JSONObject().put("expires_at", iso).put("expires_in", 3600000));
        check(end == epoch, "initial job honors the explicit ISO expiry before duration");
        java.lang.reflect.Method device = QoderAuth.class.getDeclaredMethod("refreshExpiry", JSONObject.class, boolean.class);
        device.setAccessible(true);
        before = System.currentTimeMillis();
        end = (Long) device.invoke(null, new JSONObject().put("expires_in", 3600), true);
        check(end >= before + 3600000 && end <= System.currentTimeMillis() + 3600000,
                "device expiry remains seconds");
    }
    static int numericExpiryContract() throws Exception {
        java.lang.reflect.Method initial = QoderAuth.class.getDeclaredMethod("expiresAt", JSONObject.class);
        java.lang.reflect.Method device = QoderAuth.class.getDeclaredMethod("refreshExpiry", JSONObject.class, boolean.class);
        java.lang.reflect.Method parse = QoderAuth.class.getDeclaredMethod("parseEpoch", Object.class);
        initial.setAccessible(true); device.setAccessible(true); parse.setAccessible(true);
        long wanted = (System.currentTimeMillis() + 7200000L) / 1000L * 1000L;
        long seconds = wanted / 1000L; int checks = 0;
        Object integralSeconds = Long.valueOf(seconds);
        if (seconds >= Integer.MIN_VALUE && seconds <= Integer.MAX_VALUE) integralSeconds = Integer.valueOf((int) seconds);
        Object[] values = {Long.valueOf(wanted), Double.valueOf(wanted), new java.math.BigDecimal(wanted + ".0"),
                integralSeconds, Long.valueOf(seconds), Double.valueOf(seconds), new java.math.BigDecimal(seconds + ".0"),
                Long.toString(wanted), Long.toString(seconds), java.time.Instant.ofEpochMilli(wanted).toString()};
        for (Object value : values) {
            check((Long) initial.invoke(null, new JSONObject().put("expire_time", value)) == wanted,
                    "initial job accepts a valid absolute timestamp regardless of numeric representation"); checks++;
            check((Long) device.invoke(null, new JSONObject().put("expires_at", value), true) == wanted,
                    "device keeps valid absolute timestamp compatibility"); checks++;
        }
        String scientific = new java.math.BigDecimal(wanted).scaleByPowerOfTen(-12).toPlainString() + "e12";
        for (String number : new String[]{wanted + ".0", scientific}) {
            JSONObject job = new JSONObject("{\"expire_time\":" + number + "}");
            JSONObject dev = new JSONObject("{\"expires_at\":" + number + "}");
            check((Long) initial.invoke(null, job) == wanted, "actual decimal/scientific JSON job response accepted"); checks++;
            check((Long) device.invoke(null, dev, true) == wanted, "actual decimal/scientific JSON device response accepted"); checks++;
        }
        check((Long) parse.invoke(null, Long.valueOf(Long.MAX_VALUE)) == Long.MAX_VALUE, "integer epoch handling does not lose precision"); checks++;
        check((Long) parse.invoke(null, new java.math.BigDecimal("1e40")) == 0L, "unrepresentable numeric epoch stays invalid"); checks++;
        check((Long) parse.invoke(null, "1e40") == 0L, "invalid scientific strings are not guessed into dates"); checks++;
        return checks;
    }
    public static void main(String[] args) throws Exception {
        initialExpiryContract();
        System.out.println("Qoder numeric expiry checks=" + numericExpiryContract());
        QoderAuth.SignIn old = new QoderAuth.SignIn(); old.uid="uid"; old.machineId="machine"; old.access="old-access"; old.refresh="old-refresh"; old.deviceToken="old-access"; old.deviceRefresh="old-refresh"; old.deviceChat=true; old.expiresAt=1;
        Transport first=new Transport(200,listing(false));
        QoderAuth.Catalog initial=QoderAuth.readCatalog(old,first);
        check(initial.freeView && initial.models.length()==2 && first.last.disconnected,"free view");
        Transport rotation=new Transport(200,"{\"token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}");
        long now=System.currentTimeMillis(); QoderAuth.SignIn next=QoderAuth.refreshCandidate(old,rotation);
        check(rotation.last.getURL().getPath().endsWith("/deviceToken/refresh"),"device endpoint");
        check(new JSONObject(rotation.last.sent.toString("UTF-8")).getString("refresh_token").equals("old-refresh"),"refresh request");
        check(next!=old && next.access.equals("new-access") && next.deviceToken.equals(next.access) && next.deviceRefresh.equals(next.refresh),"rotated candidate");
        check(old.access.equals("old-access") && old.refresh.equals("old-refresh") && old.expiresAt==1,"old untouched");
        check(next.expiresAt>=now+3599000 && next.expiresAt<=System.currentTimeMillis()+3601000,"device seconds expiry");
        QoderAuth.Catalog refreshed=QoderAuth.readCatalog(next,new Transport(200,listing(true)));
        check(!refreshed.freeView && refreshed.models.length()==12 && refreshed.meta.getJSONObject("synthetic0").getString("display_name").equals("name-synthetic0"),"full catalog");
        QoderAuth.Catalog still=QoderAuth.readCatalog(next,new Transport(200,listing(false)));
        check(still.freeView && still.models.length()==2,"still free stays free");
        Transport bad=new Transport(401,"secret-response");
        try { QoderAuth.refreshCandidate(old,bad); throw new AssertionError("status accepted"); } catch(QoderAuth.QoderHttpException e) { check(e.status==401 && !e.toString().contains("secret"),"sanitized status"); }
        check(bad.last.disconnected && old.access.equals("old-access"),"failure isolated");
        try { QoderAuth.refreshCandidate(old,new Transport(200,"{\"token\":\"secret-incomplete\"}")); throw new AssertionError("incomplete accepted"); } catch(IOException e) { check(!e.toString().contains("secret"),"sanitized pair"); }
        try { QoderAuth.readCatalog(old,new Transport(200,"{\"app\":[{\"key\":\"a\",\"enable\":true},{\"key\":\"bad\"}]}")); throw new AssertionError("invalid accepted"); } catch(IOException expected) { }
        JSONObject source=new JSONObject().put("app",new JSONArray().put(entry("a",true,1)).put(entry("b",true,1))).put("chat",new JSONArray().put(entry("a",false,1)).put(entry("c",true,1)));
        QoderAuth.Catalog ordered=QoderAuth.readCatalog(old,new Transport(200,source.toString()));
        check(ordered.models.toString().equals("[\"a\",\"c\",\"b\"]") && ordered.meta.getJSONObject("a").getBoolean("enable"),"order dedupe app wins");
        QoderAuth.SignIn job=new QoderAuth.SignIn(); job.refresh="job-old"; job.deviceToken="unchanged"; job.deviceRefresh="unchanged-refresh";
        Transport jt=new Transport(200,"{\"token\":\"job-new\",\"refresh_token\":\"job-new-refresh\",\"expires_in\":3600000}");
        now=System.currentTimeMillis(); QoderAuth.SignIn jn=QoderAuth.refreshCandidate(job,jt);
        check(jt.last.getURL().getPath().endsWith("/jobToken/refresh") && jn.deviceToken.equals("unchanged") && jn.expiresAt>=now+3599000 && jn.expiresAt<=System.currentTimeMillis()+3601000,"job refresh and milliseconds");
        System.out.println("QoderCatalogIntegrityHttpTest OK");
    }
}
