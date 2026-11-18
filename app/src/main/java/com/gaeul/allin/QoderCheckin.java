package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Qoder CN's user-initiated campaign flow. No client device identity is synthesized. */
final class QoderCheckin {
    static final String PROVIDER = "qoder_cn";
    private static final String URL = "https://openapi.qoder.com.cn/sash/api/v1/me/campaigns";

    private QoderCheckin() { }

    static boolean eligible(JSONObject account) {
        return !token(account).isEmpty();
    }

    private static String token(JSONObject account) {
        String device = account.optString("deviceToken", "").trim();
        if (!device.isEmpty()) return device;
        if (account.optBoolean("deviceChat", false) || "pat".equals(account.optString("authSource"))) {
            return account.optString("token", "").trim();
        }
        return "";
    }

    static JSONObject state(String outcome, String message) {
        return new JSONObject().put("outcome", outcome).put("message", message).put("claimPossible", false);
    }

    static JSONObject status(JSONObject account, long now, GatewayServer.VendorConnectionFactory transport) throws IOException {
        JSONObject body = call(account, null, transport);
        if (body.optInt("httpStatus") != 200) {
            int code = body.optInt("httpStatus");
            return state(code == 401 || code == 403 ? "auth_required" : "failed", "Qoder CN 活动查询失败（HTTP " + code + "）");
        }
        JSONArray campaigns = body.optJSONArray("campaigns");
        if (campaigns == null) throw new IOException("Missing campaign list");
        JSONObject current = null;
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject item = campaigns.optJSONObject(i);
            if (item == null) throw new IOException("Malformed campaign");
            if (!"CLAIM_BENEFIT".equals(item.optString("actionType"))) continue;
            String id = item.optString("campaignId", "");
            long start = AccountRoutingPolicy.time(item.opt("startAt"));
            long end = AccountRoutingPolicy.time(item.opt("endAt"));
            if (!validId(id) || start <= 0 || end <= start) throw new IOException("Malformed campaign window");
            if (now < start || now >= end) continue;
            String claimStatus = item.optString("claimStatus", "");
            if (!"CLAIMED".equals(claimStatus) && !"CLAIMABLE".equals(claimStatus)) continue;
            if (current != null) throw new IOException("Ambiguous campaign window");
            boolean claimed = "CLAIMED".equals(claimStatus);
            current = state(claimed ? "done" : "claimable", claimed ? "本轮活动已领取" : "本轮活动可领取")
                    .put("campaignId", id).put("campaignStart", start).put("campaignEnd", end)
                    .put("verified", claimed).put("claimPossible", !claimed);
            if (claimed) setCredit(current, item);
        }
        return current == null ? state("inactive", "当前账号和设备未查到可领取的 Qoder CN 活动，可在官方客户端核对") : current;
    }

    static JSONObject claim(JSONObject account, String id, GatewayServer.VendorConnectionFactory transport) throws IOException {
        if (!validId(id)) throw new IOException("Invalid campaign ID");
        JSONObject body = call(account, id, transport);
        JSONObject data = body.optJSONObject("data");
        String result = body.optString("status", data == null ? "" : data.optString("status", ""));
        if (body.optInt("httpStatus") != 200 || !"CLAIMED".equals(result)) throw new IOException("Claim outcome uncertain");
        JSONObject answer = state("claimed", "官方已确认本轮领取").put("verified", true);
        setCredit(answer, body.optJSONObject("benefit") != null || data == null ? body : data);
        return answer;
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static void setCredit(JSONObject answer, JSONObject source) {
        JSONObject benefit = source.optJSONObject("benefit");
        Object value = benefit == null ? null : benefit.opt("amount");
        if (value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() > 0) {
            answer.put("credit", value).put("unit", "积分");
        }
    }

    private static JSONObject call(JSONObject account, String id, GatewayServer.VendorConnectionFactory transport) throws IOException {
        String access = token(account);
        if (access.isEmpty()) throw new IOException("Missing Qoder campaign credential");
        HttpURLConnection conn = transport.open(id == null ? URL : URL + "/" + id + "/claim");
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestMethod(id == null ? "GET" : "POST");
            conn.setRequestProperty("Authorization", "Bearer " + access);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cosy-ClientType", "10");
            conn.setRequestProperty("User-Agent", "Qoder");
            if (id != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            }
            int code = conn.getResponseCode();
            if (code != 200) return new JSONObject().put("httpStatus", code);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (out.size() + n > 262144) throw new IOException("Campaign response too large");
                    out.write(buffer, 0, n);
                }
                JSONTokener parser = new JSONTokener(out.toString("UTF-8"));
                Object parsed = parser.nextValue();
                if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("Invalid campaign response");
                JSONObject result = (JSONObject) parsed;
                if (result.has("code") && !"0".equals(result.optString("code"))) throw new IOException("Campaign returned a business error");
                return result.put("httpStatus", code);
            }
        } finally {
            conn.disconnect();
        }
    }
}
