package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen upstream 0.2.1 contracts; all transports are in-memory, no Google requests. */
public final class AntigravityCompatibilityOfflineTest {
    static int checks, failures;
    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + what); }
    }
    static JSONObject payload() {
        return new JSONObject().put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "synthetic compatibility request")));
    }
    static final class Wire implements AntigravityAuth.Transport {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int opens;
        public AntigravityAuth.Link open(String url, String host, int port, int connect, int read) {
            opens++;
            final byte[] response = "HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
            return new AntigravityAuth.Link() {
                public InputStream in() { return new ByteArrayInputStream(response); }
                public OutputStream out() { return bytes; }
                public void close() { }
            };
        }
    }
    public static void main(String[] args) throws Exception {
        check(AntigravityAuth.SCOPES.contains("https://www.googleapis.com/auth/aicode"), "new consent requests aicode");
        JSONObject body = AntigravityClient.buildEnvelope(payload(), "gemini-3.8-flash", "synthetic-project");
        JSONObject second = AntigravityClient.buildEnvelope(payload(), "gemini-3.8-flash", "synthetic-project");
        check(body.optString("requestId").startsWith("agent/"), "new requestId trajectory format");
        JSONObject labels = body.getJSONObject("request").optJSONObject("labels");
        check(labels != null, "new request carries labels");
        if (labels != null) {
            check(labels.optString("cascade_id").equals(second.getJSONObject("request").getJSONObject("labels").optString("cascade_id")), "same conversation has stable cascade");
            check("false".equals(labels.optString("used_non_gemini_model")), "Gemini maker label");
        }
        check("agent".equals(body.optString("requestType")) && "antigravity".equals(body.optString("userAgent")), "service envelope retained");
        Wire wire = new Wire();
        AntigravityAuth.Dispatch result = AntigravityAuth.dispatch(new String[]{"https://one.invalid", "https://two.invalid"},
                AntigravityAuth.STREAM_PATH, "synthetic-only", body.toString().getBytes(StandardCharsets.UTF_8),
                1000, 1000, wire, AntigravityAuth.Cancellation.NONE);
        try {
            String raw = wire.bytes.toString("UTF-8");
            check(raw.contains("(aidev_client; os_type=") && raw.contains("; cl=1021296516)"), "actual wire has new User-Agent format/build");
            check(!raw.contains("Accept: text/event-stream"), "no legacy explicit SSE Accept header");
            check(wire.opens == 1 && result.sendCount == 1 && result.resp.status == 403, "403 is preserved without replay or alternate base");
            check(raw.endsWith(body.toString()), "wire body is the built envelope byte-for-byte");
        } finally { if (result.resp != null) result.resp.close(); }
        JSONObject oracle = new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), StandardCharsets.UTF_8));
        check(AntigravityAuth.APP_VERSION.equals(oracle.getString("clientVersion")), "version matches independent upstream oracle");
        check(AntigravityAuth.USER_AGENT.contains("cl=" + oracle.getString("changeList")), "wire build matches oracle");
        String serialized = body.toString(); int previous = -1;
        JSONArray order = oracle.getJSONArray("envelopeOrder");
        for (int i=0;i<order.length();i++) {
            int at = serialized.indexOf(JSONObject.quote(order.getString(i)) + ":");
            check(at > previous, "actual serialized root order: " + order.getString(i)); previous = at;
        }
        Wire custom = new Wire();
        AntigravityAuth.Resp customResponse = AntigravityAuth.request("https://custom.invalid", "POST", "synthetic", null,
                "application/json", new byte[0], 1000, 1000, custom, AntigravityAuth.Cancellation.NONE, 1, "custom-safe-agent");
        customResponse.close();
        check(custom.bytes.toString("UTF-8").contains("User-Agent: custom-safe-agent"), "ua argument reaches captured wire");
        try {
            AntigravityAuth.request("https://custom.invalid", "POST", "synthetic", null, "application/json", new byte[0],
                    1000, 1000, custom, AntigravityAuth.Cancellation.NONE, 1, "bad" + (char)13 + (char)10 + "Injected: x");
            check(false, "header injection must reject");
        } catch (IOException expected) { check(custom.opens == 1, "bad user agent rejected before transport opens"); }
        JSONObject modelReply = new JSONObject().put("models", new JSONObject()
                .put("gemini-3.8-flash-low", new JSONObject().put("model", "MODEL_LOW").put("modelProvider", "MODEL_PROVIDER_GOOGLE"))
                .put("gemini-3.8-flash-high", new JSONObject().put("model", "MODEL_HIGH").put("modelProvider", "MODEL_PROVIDER_GOOGLE")));
        JSONArray models = AntigravityAuth.fetchModels(new JSONObject().put("id", "synthetic-catalog"), "synthetic", "project",
                (url, token, request) -> modelReply);
        JSONObject families = new JSONObject(); AntigravityAuth.collapseFamilies(models, families);
        JSONObject variants = families.getJSONObject("gemini-3.8-flash").getJSONObject("variantMetadata");
        for (String level : new String[]{"low", "high"}) {
            String id = "gemini-3.8-flash-" + level;
            JSONObject envelope = AntigravityClient.buildEnvelope(payload(), id, "project", variants.getJSONObject(id));
            check(("MODEL_" + level.toUpperCase(java.util.Locale.ROOT)).equals(envelope.getJSONObject("request").getJSONObject("labels").optString("model_enum")),
                    "enum follows selected effort " + level);
        }
        check(!body.getJSONObject("request").getJSONObject("labels").has("model_enum"), "missing enum is omitted rather than invented");
        check(AntigravityProtocol.onboardBody(null).toString().equals(oracle.getJSONObject("onboardDefault").toString()), "onboard matches frozen upstream body");
        System.out.println("AntigravityCompatibilityOfflineTest checks=" + checks + " failures=" + failures);
        if (failures > 0) throw new AssertionError("compatibility failures=" + failures);
    }
}
