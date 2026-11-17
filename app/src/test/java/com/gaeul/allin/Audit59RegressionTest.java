package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic production-handler/HTTP-wire regressions; never contacts a vendor. */
public final class Audit59RegressionTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    interface Case { void run() throws Exception; }
    static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    static final class Fixture implements AutoCloseable {
        final GatewayServer server;
        Fixture() throws Exception {
            JSONObject config = new JSONObject().put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", "fixture-inference-only"))
                    .put("providers", new JSONArray()).put("groups", new JSONArray())
                    .put("checkin", new JSONObject().put("autoEnabled", false));
            server = RoutingSetupOfflineTest.server(config);
            server.setVendorConnectionsForTests(url -> { throw new AssertionError("Unexpected vendor transport"); });
        }
        public void close() { server.stop(); NetworkPolicy.configure(null); }
    }
    static final class Reply extends HttpURLConnection {
        final int status; final byte[] bytes;
        Reply(String url, int status, String body) throws Exception {
            super(new URL(url)); this.status = status; bytes = body.getBytes(StandardCharsets.UTF_8);
        }
        public void connect() { throw new AssertionError("No real connection permitted"); }
        public boolean usingProxy() { return false; }
        public void disconnect() { }
        public int getResponseCode() { return status; }
        public long getContentLengthLong() { return bytes.length; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public InputStream getErrorStream() { return status >= 400 ? new ByteArrayInputStream(bytes) : null; }
    }
    /** Pinned raccoon plugin index.mjs:578-598 and 638-663: 403 is not sign-in expiry. */
    static void raccoonBalance() throws Exception {
        for (int firstStatus : new int[]{403, 500, 401, 200}) {
            try (Fixture f = new Fixture()) {
                List<String> sent = new ArrayList<>();
                f.server.setVendorConnectionsForTests(url -> {
                    String path = new URL(url).getPath(); sent.add(path);
                    check(path.equals("/api/web/points/v1/balance") || path.equals("/api/web/office/v3/setting_info"),
                            "Only balance and its documented fallback may be sent");
                    String body = path.endsWith("setting_info") ? "{\"code\":0,\"data\":{\"available_points\":123}}"
                            : firstStatus == 200 ? "{\"code\":200001}" : "{\"error\":\"synthetic refusal\"}";
                    try { return new Reply(url, path.endsWith("setting_info") ? 200 : firstStatus, body); }
                    catch (Exception e) { throw new IOException("synthetic construction failed", e); }
                });
                Method api = GatewayServer.class.getDeclaredMethod("raccoonApi", String.class, String.class);
                api.setAccessible(true);
                // No account id means no refresh-token path; the test cannot consume any refresh.
                RaccoonAccountService service = (RaccoonAccountService) invoke(api, f.server, "", "fixture-raccoon-access");
                if (firstStatus == 401 || firstStatus == 200) {
                    try { service.balance(); throw new AssertionError("Expired sign-in accepted"); }
                    catch (RaccoonAccountService.Failure expected) { check(expected.expired, "Explicit expiry kept"); }
                    check(sent.size() == 1, "An expired sign-in must not call the reward-capable fallback");
                } else {
                    long balance;
                    try { balance = service.balance(); }
                    catch (RaccoonAccountService.Failure bad) {
                        throw new AssertionError("An ordinary " + firstStatus + " must not be marked expired or block fallback");
                    }
                    check(balance == 123, "Fallback returns the actual stated balance");
                    check(sent.equals(Arrays.asList("/api/web/points/v1/balance", "/api/web/office/v3/setting_info")),
                            "Exactly one primary and one fallback, no onboarding grant or refresh");
                }
            }
        }
    }
    static byte[] headWire(NanoHTTPD.Response response) throws Exception {
        response.setRequestMethod(NanoHTTPD.Method.HEAD);
        Method send = NanoHTTPD.Response.class.getDeclaredMethod("send", OutputStream.class); send.setAccessible(true);
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); invoke(send, response, wire); return wire.toByteArray();
    }
    /** Measure the actual NanoHTTPD output, not merely the selected server branch. */
    static void headBodies() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String path : new String[]{"/api/routing", "/api/config", "/api/status", "/v1/models", "/api/health",
                    "/v1/chat/completions", "/v1/messages", "/v1/responses", "/responses", "/v1/images/generations",
                    "/backend-api/codex/responses", "/backend-api/codex/responses/compact", "/backend-api/codex/models"}) {
                NanoHTTPD.Response response = f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1", path, "HEAD", "", null));
                byte[] bytes = headWire(response);
                String wire = new String(bytes, StandardCharsets.UTF_8); int end = wire.indexOf("\r\n\r\n");
                check(end >= 0, "HEAD response headers complete: " + path);
                check(bytes.length == end + 4, "HEAD must emit zero body bytes even on API/model/error routes: " + path);
                check(!wire.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked"), "No HEAD chunk terminator");
                check(f.server.activeForwardRequests() == 0, "HEAD release cannot leak the model admission gate");
            }
        }
    }
    static void giftConfigHandler() throws Exception {
        try (Fixture f = new Fixture()) {
            Field config = GatewayServer.class.getDeclaredField("configJson"); config.setAccessible(true);
            JSONObject root = (JSONObject) config.get(f.server);
            String secret = "fixture-gift-handler-secret-0000";
            root.getJSONArray("providers").put(new JSONObject().put("id", "zcode")
                    .put("accounts", new JSONArray().put(new JSONObject().put("id", "gift-fixture").put("giftJwt", secret))));
            NanoHTTPD.Response response = f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/api/config", "GET", "", null));
            check(response.getStatus().getRequestStatus() == 200, "Actual config handler succeeds");
            String text;
            try (InputStream in = response.getData()) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            check(!text.contains(secret), "Actual config handler must not return gift JWT bytes");
            check(new JSONObject(text).getJSONArray("providers").getJSONObject(0).getJSONArray("accounts")
                    .getJSONObject(0).getString("giftJwt").startsWith("allin-secret-ref:"), "Actual config handler returns a bound reference");
        }
    }
    static void antigravityBases() throws Exception {
        String oldChat = AntigravityAuth.chatBase, oldLoad = AntigravityAuth.loadBase;
        try {
            AntigravityAuth.applyBases(AntigravityAuth.REQ_BASE, AntigravityAuth.LOAD_BASE);
            for (String invalid : new String[]{"https://fixture.invalid", AntigravityAuth.REQ_BASE + ".fixture.invalid",
                    AntigravityAuth.REQ_BASE + "@fixture.invalid", AntigravityAuth.REQ_BASE + "/extra",
                    AntigravityAuth.REQ_BASE + "?redirect=x", AntigravityAuth.REQ_BASE + "#x", AntigravityAuth.REQ_BASE + ":444"}) {
                AntigravityAuth.switchChatBase(invalid);
                AntigravityAuth.applyBases(invalid, invalid);
                List<String> calls = new ArrayList<>();
                AntigravityAuth.fetchModels(new JSONObject().put("id", "fixture-base"), "fixture-access", "fixture-project",
                        (url, token, body) -> { calls.add(url); return new JSONObject().put("models", new JSONObject()); });
                check(calls.equals(Collections.singletonList(AntigravityAuth.REQ_BASE + "/v1internal:fetchAvailableModels")),
                        "Configured non-official base must not receive a catalog request");
                check(AntigravityAuth.LOAD_BASE.equals(AntigravityAuth.loadBase), "Invalid load base ignored");
            }
            AntigravityAuth.applyBases(AntigravityAuth.LOAD_BASE + "/", AntigravityAuth.REQ_BASE + "/");
            check(AntigravityAuth.LOAD_BASE.equals(AntigravityAuth.chatBase)
                    && AntigravityAuth.REQ_BASE.equals(AntigravityAuth.loadBase), "Both documented official bases remain selectable");
        } finally { AntigravityAuth.applyBases(oldChat, oldLoad); }
    }
    static final class CountedInput extends InputStream {
        long remaining, readCount; boolean closed;
        CountedInput(long size) { remaining = size; }
        public int available() { return (int) Math.min(Integer.MAX_VALUE, remaining); }
        public int read() { if (remaining == 0) return -1; remaining--; readCount++; return 'x'; }
        public int read(byte[] b, int off, int len) {
            if (len == 0) return 0; if (remaining == 0) return -1;
            int n = (int) Math.min(remaining, len); Arrays.fill(b, off, off + n, (byte) 'x');
            remaining -= n; readCount += n; return n;
        }
        public void close() { closed = true; }
    }
    static void catpawBoundedRead() throws Exception {
        Method read; boolean withTimeout;
        try { read = CatPawAuth.class.getDeclaredMethod("readAll", InputStream.class, int.class); withTimeout = true; }
        catch (NoSuchMethodException old) { read = CatPawAuth.class.getDeclaredMethod("readAll", InputStream.class); withTimeout = false; }
        read.setAccessible(true);
        CountedInput huge = new CountedInput(BoundedResponse.MAX_BYTES + 4096L); boolean rejected = false;
        try { if (withTimeout) invoke(read, null, huge, 20000); else invoke(read, null, huge); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "CatPaw ordinary response must reject excess before unbounded accumulation");
        check(huge.readCount == BoundedResponse.MAX_BYTES + 1L && huge.closed,
                "Oversized response stops at bound plus sentinel byte and closes the stream");
        CountedInput small = new CountedInput(131072);
        String text = (String) (withTimeout ? invoke(read, null, small, 20000) : invoke(read, null, small));
        check(text.length() == 131072 && small.closed, "Legitimate ordinary response remains complete");
    }
    static void catpawErrorClassification(int status) throws Exception {
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        NanoHTTPD peer = new NanoHTTPD("127.0.0.1", 0) {
            public Response serve(IHTTPSession session) {
                received.incrementAndGet();
                byte[] malformedUtf8 = new byte[]{(byte) 0xc3};
                return newFixedLengthResponse(Response.Status.lookup(status), "application/json",
                        new ByteArrayInputStream(malformedUtf8), malformedUtf8.length);
            }
        };
        peer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        try {
            Method get = CatPawAuth.class.getDeclaredMethod("getJson", String.class, Map.class, int.class);
            get.setAccessible(true);
            try {
                invoke(get, null, "http://127.0.0.1:" + peer.getListeningPort() + "/read-only-fixture", Collections.emptyMap(), 5000);
                throw new AssertionError("An HTTP refusal must not be parsed as success");
            } catch (CatPawAuth.AccountFailure expected) {
                check(expected.httpStatus == status, "A malformed error body cannot erase the known HTTP status");
                check(expected.expired() == (status == 401), "Only a known authentication rejection is expired here");
            } catch (IOException changedClassification) {
                check(received.get() == 1, "Malformed-error fixture reached the loopback server exactly once");
                check("invalid response UTF-8".equals(changedClassification.getMessage()),
                        "The new regression must be strict UTF-8 decoding, not a fixture transport failure");
                throw new AssertionError("Known HTTP " + status + " became " + changedClassification.getClass().getSimpleName()
                        + " instead of AccountFailure after the bounded-reader change");
            }
            check(received.get() == 1, "Exactly one loopback request, no retry or vendor call");
        } finally { peer.stop(); }
    }
    static void catpawPayload(int status, byte[] payload, Integer expectedCode, String expectedReadFailure) throws Exception {
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        NanoHTTPD peer = new NanoHTTPD("127.0.0.1", 0) {
            public Response serve(IHTTPSession session) {
                received.incrementAndGet();
                return newFixedLengthResponse(Response.Status.lookup(status), "application/json",
                        new ByteArrayInputStream(payload), payload.length);
            }
        };
        peer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        try {
            Method get = CatPawAuth.class.getDeclaredMethod("getJson", String.class, Map.class, int.class);
            get.setAccessible(true); Exception failure = null; Object result = null;
            try { result = invoke(get, null, "http://127.0.0.1:" + peer.getListeningPort() + "/fixture", Collections.emptyMap(), 10000); }
            catch (Exception caught) { failure = caught; }
            if (status < 200 || status >= 300) {
                check(failure instanceof CatPawAuth.AccountFailure, "An unreadable diagnostic body keeps the actual HTTP refusal");
                CatPawAuth.AccountFailure refused = (CatPawAuth.AccountFailure) failure;
                check(refused.httpStatus == status && java.util.Objects.equals(refused.businessCode, expectedCode),
                        "HTTP status and only a valid integral business code are preserved");
                boolean expired = status == 401 || (expectedCode != null && (expectedCode == 4010 || expectedCode == 4011));
                check(refused.expired() == expired, "No invented expired sign-in from an unreadable diagnostic");
            } else if (expectedReadFailure != null) {
                check(failure instanceof IOException && !(failure instanceof CatPawAuth.AccountFailure)
                        && expectedReadFailure.equals(failure.getMessage()), "Successful HTTP bodies remain strictly bounded and UTF-8 checked");
                check(result == null, "An invalid successful body is never handed out as parsed JSON");
            } else {
                check(failure == null && result instanceof JSONObject, "Valid successful JSON is unchanged");
                check("中文与表情😀".equals(((JSONObject) result).getString("message")), "Valid multibyte payload preserved");
            }
            check(received.get() == 1, "Payload verification sends exactly one loopback GET");
        } finally { peer.stop(); }
    }
    static void catpawDiagnosticVariants() throws Exception {
        catpawPayload(403, "{\"code\":4011}".getBytes(StandardCharsets.UTF_8), 4011, null);
        for (String text : new String[]{"", "not-json", "{\"code\":4011.5}", "{\"code\":\"4011\"}"})
            catpawPayload(403, text.getBytes(StandardCharsets.UTF_8), null, null);
        byte[] excessive = new byte[BoundedResponse.MAX_BYTES + 4096]; Arrays.fill(excessive, (byte) 'x');
        catpawPayload(403, excessive, null, null);
        catpawPayload(200, excessive, null, "response body too large");
        catpawPayload(200, new byte[]{(byte) 0xc3}, null, "invalid response UTF-8");
        catpawPayload(200, "{\"message\":\"中文与表情😀\"}".getBytes(StandardCharsets.UTF_8), null, null);
    }
    public static void main(String[] args) throws Exception {
        int failures = 0;
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("raccoon balance classification", Audit59RegressionTest::raccoonBalance);
        cases.put("HEAD actual wire", Audit59RegressionTest::headBodies);
        cases.put("gift config handler", Audit59RegressionTest::giftConfigHandler);
        cases.put("official upstream bases", Audit59RegressionTest::antigravityBases);
        cases.put("CatPaw bounded read", Audit59RegressionTest::catpawBoundedRead);
        cases.put("CatPaw diagnostic and success bodies", Audit59RegressionTest::catpawDiagnosticVariants);
        for (int status : new int[]{401, 403, 429, 500})
            cases.put("CatPaw error classification " + status, () -> catpawErrorClassification(status));
        for (Map.Entry<String, Case> item : cases.entrySet()) {
            try { item.getValue().run(); System.out.println("PASS " + item.getKey()); }
            catch (Throwable error) { failures++; System.out.println("FAIL " + item.getKey() + ": " + error.getClass().getSimpleName() + " " + error.getMessage()); }
        }
        if (failures > 0) throw new AssertionError("Audit59 failures=" + failures);
        System.out.println("Audit59RegressionTest PASS checks=" + checks);
    }
}
