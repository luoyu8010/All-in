package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An explicit WorkBuddy model-window refusal, kept for the life of the gateway. */
final class WorkBuddyQuotaCooldown {
    private static final String MODEL = "deepseek-v4.1-flash";
    private static final long MAX_WINDOW_MS = 24 * 60 * 60 * 1000L + 5 * 60 * 1000L;
    private static final int MAX_ENTRIES = 256;
    private static final Pattern RESET = Pattern.compile(
            "(?:您的使用量已超出频率限制[，,]\\s*将在\\s*"
                    + "|usage exceeds frequency limit,\\s*but don['’]t worry,\\s*your usage will reset at\\s+)"
                    + "(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})\\s*"
                    + "(?:UTC|GMT)([+-])(\\d{1,2})(?::?(\\d{2}))?(?![\\w:+-])",
            Pattern.CASE_INSENSITIVE);
    private final Map<String, Long> deadlines = new LinkedHashMap<>();

    static String window(String provider, String model) {
        if (!("workbuddy".equals(provider) || "workbuddy-ai".equals(provider))) return null;
        return MODEL.equals(model) || (MODEL + "-sg").equals(model) ? MODEL : null;
    }

    static long resetAt(int status, String errorBody, long now) {
        if (status != 429 || errorBody == null || errorBody.length() > 65536) return 0;
        String message;
        try {
            JSONObject body = new JSONObject(errorBody);
            JSONObject error = body.optJSONObject("error");
            if (creditExhausted(body) || "11140".equals(code(body))) return 0;
            Object value = error == null ? null : error.opt("message");
            if (!(value instanceof String)) value = body.opt("msg");
            if (!(value instanceof String)) value = body.opt("message");
            if (!(value instanceof String)) value = body.opt("error");
            if (!(value instanceof String)) return 0;
            message = (String) value;
        } catch (Exception invalidJson) {
            return 0;
        }
        Matcher match = RESET.matcher(message);
        if (!match.find()) return 0;
        try {
            int hours = Integer.parseInt(match.group(8));
            int minutes = match.group(9) == null ? 0 : Integer.parseInt(match.group(9));
            if (minutes > 59 || hours > 14 || (hours == 14 && minutes != 0)) return 0;
            int sign = "-".equals(match.group(7)) ? -1 : 1;
            Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            calendar.clear();
            calendar.setLenient(false);
            calendar.set(number(match, 1), number(match, 2) - 1, number(match, 3),
                    number(match, 4), number(match, 5), number(match, 6));
            long reset = calendar.getTimeInMillis() - sign * (hours * 60L + minutes) * 60000L;
            return reset > now && reset - now <= MAX_WINDOW_MS ? reset : 0;
        } catch (IllegalArgumentException invalidTime) {
            return 0;
        }
    }

    static boolean creditExhausted(String errorBody) {
        try { return creditExhausted(new JSONObject(errorBody)); }
        catch (Exception invalidJson) { return false; }
    }

    private static boolean creditExhausted(JSONObject body) {
        String code = code(body);
        return "14018".equals(code) || "credits_exhausted".equals(code);
    }

    private static String code(JSONObject body) {
        JSONObject error = body.optJSONObject("error");
        return error == null ? body.optString("code", "") : error.optString("code", body.optString("code", ""));
    }

    private static int number(Matcher match, int group) {
        return Integer.parseInt(match.group(group));
    }

    synchronized long active(String key, long now) {
        Long reset = deadlines.get(key);
        if (reset == null) return 0;
        if (reset > now) return reset;
        deadlines.remove(key);
        return 0;
    }

    synchronized void observe(String key, long reset, long now) {
        if (reset <= now) return;
        deadlines.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (deadlines.size() == MAX_ENTRIES && !deadlines.containsKey(key)) {
            deadlines.remove(deadlines.keySet().iterator().next());
        }
        deadlines.merge(key, reset, Math::max);
    }
}
