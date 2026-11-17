package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual loopback import handler + atomic config file, entirely synthetic credentials. */
public final class CodexApiKeyPoolHttpTest {
    static final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger(), imports = new java.util.concurrent.atomic.AtomicInteger();
    static final String A = "sk-alpha-fixture-pool-material-0000";
    static final String B = "sk-bravo-fixture-pool-material-0000";
    static final String C = "sk-alpha-fixture-pool-material-9999"; // Same prefix as A, different full key.
    static void check(boolean value, String why) { checks.incrementAndGet(); if (!value) throw new AssertionError(why); }
    static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    static JSONObject keyAccount(String id, String key, String label) {
        return new JSONObject().put("id", id).put("type", "apikey").put("authMode", "apikey")
                .put("token", key).put("access", key).put("accountId", "").put("email", label)
                .put("name", label).put("addedAt", 1234L).put("enabled", true).put("expires", 0L);
    }
    static final class Fixture extends NanoHTTPD implements AutoCloseable {
        final Path directory; final File configFile; final GatewayServer inner;
        Fixture() throws Exception { this(new JSONArray()); }
        Fixture(JSONArray accounts) throws Exception {
            super("127.0.0.1", 0);
            directory = Files.createTempDirectory("codex-key-pool-"); configFile = directory.resolve("config.json").toFile();
            JSONArray keys = new JSONArray();
            for (int i = 0; i < accounts.length(); i++) keys.put(accounts.getJSONObject(i).getString("access"));
            JSONObject provider = new JSONObject().put("id", "codex").put("protocol", "codex")
                    .put("enabled", true).put("status", "ported").put("models", new JSONArray().put("fixture-model"))
                    .put("accounts", accounts).put("keys", keys);
            JSONObject config = new JSONObject().put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", "fixture-inference-auth"))
                    .put("checkin", new JSONObject().put("autoEnabled", false))
                    .put("providers", new JSONArray().put(provider)).put("groups", new JSONArray());
            inner = RoutingSetupOfflineTest.server(config);
            field("configFile").set(inner, configFile);
            inner.setVendorConnectionsForTests(url -> { throw new AssertionError("No vendor request allowed"); });
            AtomicConfigFile.write(configFile, config.toString(2));
            start(SOCKET_READ_TIMEOUT, true);
        }
        Field field(String name) throws Exception { Field f = GatewayServer.class.getDeclaredField(name); f.setAccessible(true); return f; }
        JSONObject memory() throws Exception { return (JSONObject) field("configJson").get(inner); }
        JSONObject disk() throws Exception { return new JSONObject(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8)); }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
        public void close() throws Exception {
            GatewayServer.setConfigWriteHookForTests(null); GatewayServer.setPostCommitHookForTests(null);
            stop(); inner.stop(); NetworkPolicy.configure(null);
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
            }
        }
    }
    static JSONObject provider(JSONObject root) { return root.getJSONArray("providers").getJSONObject(0); }
    static JSONObject byKey(JSONObject root, String key) {
        JSONObject found = null; JSONArray accounts = provider(root).getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject item = accounts.getJSONObject(i);
            if (key.equals(item.optString("access", item.optString("token")))) {
                check(found == null, "One account per full API key"); found = item;
            }
        }
        return found;
    }
    static JSONObject requestImport(Fixture fixture, String key) throws Exception {
        imports.incrementAndGet();
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + fixture.getListeningPort() + "/api/auth/codex/import").openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(3000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(new JSONObject().put("raw", key).toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode();
            check(status == 200, "Import management HTTP status=" + status);
            try (InputStream in = c.getInputStream()) { return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        } finally { c.disconnect(); }
    }
    static String accepted(Fixture fixture, String key) throws Exception {
        JSONObject reply = requestImport(fixture, key);
        check(Boolean.TRUE.equals(reply.opt("ok")), "Import must confirm the durable account, not merely parse a key");
        String id = reply.optString("keyId"); check(!id.isEmpty() && !"err".equals(id), "Confirmed stable account id");
        check("Codex API Key".equals(reply.optString("account")), "Public label is fixed and does not expose key bytes");
        JSONObject disk = byKey(fixture.disk(), key), memory = byKey(fixture.memory(), key);
        check(disk != null && memory != null && id.equals(disk.getString("id")) && id.equals(memory.getString("id")), "Response id agrees with disk and memory");
        check(key.equals(disk.getString("token")) && key.equals(memory.getString("token")), "Both access and mirror token keep their complete material");
        return id;
    }
    static void distinctAndReimport() throws Exception {
        try (Fixture f = new Fixture()) {
            String first = accepted(f, A), second = accepted(f, B), third = accepted(f, C);
            check(!first.equals(second) && !first.equals(third) && !second.equals(third), "Different full keys must have different ids, including shared prefixes");
            check(provider(f.disk()).getJSONArray("accounts").length() == 3, "All three accounts coexist on disk");
            check(first.equals(accepted(f, A)), "Re-importing the same full key keeps the original id");
            check(provider(f.disk()).getJSONArray("accounts").length() == 3 && provider(f.disk()).getJSONArray("keys").length() == 3, "Re-import duplicates neither account nor mirror key");
            check(byKey(f.disk(), B) != null && byKey(f.disk(), C) != null, "Other accounts remain intact after re-import");
        }
    }
    static void legacyLabel() throws Exception {
        for (String label : new String[]{"API-Key-legacy-prefix...", "Codex API Key", "renamed-visible-label"}) {
            JSONObject original = keyAccount("old-stable-id", A, label).put("label", "keep-remark");
            try (Fixture f = new Fixture(new JSONArray().put(original))) {
                check("old-stable-id".equals(accepted(f, A)), "A legacy display label never makes the same full key a different account");
                JSONObject stored = byKey(f.disk(), A);
                check(stored.getLong("addedAt") == 1234L && "keep-remark".equals(stored.getString("label")), "Re-import preserves original identity metadata and remark");
                check(provider(f.disk()).getJSONArray("accounts").length() == 1, "Legacy re-import does not duplicate");
            }
        }
    }
    static void failedImport(boolean populated, boolean realIoFailure) throws Exception {
        JSONArray seeds = populated ? new JSONArray().put(keyAccount("one", A, "Codex API Key")).put(keyAccount("two", B, "Codex API Key")) : new JSONArray();
        try (Fixture f = new Fixture(seeds)) {
            byte[] diskBefore = Files.readAllBytes(f.configFile.toPath()); JSONObject memoryBefore = copy(f.memory());
            if (realIoFailure) f.field("configFile").set(f.inner, f.directory.resolve("missing/config.json").toFile());
            else GatewayServer.setConfigWriteHookForTests(() -> { throw new IllegalStateException("synthetic write failure"); });
            JSONObject reply;
            try { reply = requestImport(f, C); } finally { GatewayServer.setConfigWriteHookForTests(null); }
            check(Boolean.FALSE.equals(reply.opt("ok")) && !reply.has("keyId"), "A failed write cannot return successful account admission");
            check(Arrays.equals(diskBefore, Files.readAllBytes(f.configFile.toPath())), "Failed import leaves durable config bytes untouched");
            check(memoryBefore.similar(f.memory()), "Failed static-key import leaves live config unchanged too");
            check(byKey(f.memory(), C) == null, "An uncommitted static key must not silently enter memory");
        }
    }
    static void postCommitDegradation() throws Exception {
        try (Fixture f = new Fixture()) {
            GatewayServer.setPostCommitHookForTests(() -> { throw new IllegalStateException("synthetic post-commit failure"); });
            try { accepted(f, A); } finally { GatewayServer.setPostCommitHookForTests(null); }
            check(provider(f.disk()).getJSONArray("accounts").length() == 1, "A durable write stays success when a later effect degrades");
        }
    }
    static void parallelImports() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
            try {
                Future<JSONObject> a = executor.submit(() -> { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout"); return requestImport(f, A); });
                Future<JSONObject> b = executor.submit(() -> { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout"); return requestImport(f, B); });
                start.countDown(); JSONObject one = a.get(15, TimeUnit.SECONDS), two = b.get(15, TimeUnit.SECONDS);
                check(one.getBoolean("ok") && two.getBoolean("ok") && !one.getString("keyId").equals(two.getString("keyId")), "Concurrent imports keep independent ids");
                check(provider(f.disk()).getJSONArray("accounts").length() == 2 && byKey(f.disk(), A) != null && byKey(f.disk(), B) != null, "Both concurrent writes remain durable");
            } finally { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS), "Import workers terminated"); }
        }
    }
    static void identityIsolation() {
        JSONObject one = keyAccount("one", A, "same-label"), two = keyAccount("two", B, "same-label");
        check(!AccountPoolPolicy.same(one, two, "codex"), "An API key label cannot merge different credentials");
        JSONObject oauth = new JSONObject().put("type", "oauth").put("authMode", "chatgpt").put("accountId", "owner").put("email", "same-label");
        check(!AccountPoolPolicy.same(one, oauth, "codex"), "API key and OAuth identities remain separate");
        check(AccountPoolPolicy.same(oauth, copy(oauth).put("email", "renamed@example.invalid"), "codex"), "OAuth still follows authoritative accountId");
    }
    static void ambiguousKeyRecords() throws Exception {
        JSONArray duplicated = new JSONArray().put(keyAccount("one", A, "first-old-label"))
                .put(keyAccount("two", A, "second-old-label"));
        try (Fixture f = new Fixture(duplicated)) {
            byte[] before = Files.readAllBytes(f.configFile.toPath()); JSONObject memory = copy(f.memory());
            JSONObject reply = requestImport(f, A);
            check(Boolean.FALSE.equals(reply.opt("ok")) && !reply.has("keyId"), "Ambiguous full-key records are refused, not arbitrarily overwritten");
            check(Arrays.equals(before, Files.readAllBytes(f.configFile.toPath())) && memory.similar(f.memory()),
                    "Ambiguous import writes neither disk nor live configuration");
        }
    }
    static void keyMaterialAliases() {
        JSONObject current = keyAccount("one", A, "old-label");
        for (String field : new String[]{"access", "token", "apiKey", "key"}) {
            JSONObject incoming = new JSONObject().put("type", "apikey").put("email", "new-label").put(field, A);
            check(AccountPoolPolicy.same(current, incoming, "codex"), "Legacy key material aliases preserve complete-key identity");
            incoming.put(field, B);
            check(!AccountPoolPolicy.same(current, incoming, "codex"), "Aliases still distinguish different complete keys");
        }
        JSONObject conflict = copy(current).put("accountId", "owner-one");
        check(!AccountPoolPolicy.same(conflict, copy(conflict).put("accountId", "owner-two"), "codex"), "Conflicting explicit owners remain separate");
        for (String field : new String[]{"site", "domain", "enterpriseId"})
            check(!AccountPoolPolicy.same(current, copy(current).put(field, "another-scope"), "codex"), "Scope boundaries still precede material comparison");
    }
    interface Case { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Map<String, Case> tests = new LinkedHashMap<>(); tests.put("distinct keys and repeated import", CodexApiKeyPoolHttpTest::distinctAndReimport);
        tests.put("legacy labels", CodexApiKeyPoolHttpTest::legacyLabel);
        tests.put("empty pool write failure", () -> failedImport(false, false)); tests.put("existing pool write failure", () -> failedImport(true, false));
        tests.put("actual missing-directory failure", () -> failedImport(true, true)); tests.put("post-commit degradation", CodexApiKeyPoolHttpTest::postCommitDegradation);
        tests.put("parallel key imports", CodexApiKeyPoolHttpTest::parallelImports); tests.put("OAuth separation", CodexApiKeyPoolHttpTest::identityIsolation);
        tests.put("ambiguous existing key records", CodexApiKeyPoolHttpTest::ambiguousKeyRecords);
        tests.put("key aliases and scope", CodexApiKeyPoolHttpTest::keyMaterialAliases);
        int failures = 0;
        for (Map.Entry<String, Case> t : tests.entrySet()) try { t.getValue().run(); System.out.println("PASS " + t.getKey()); }
        catch (Throwable error) { failures++; System.out.println("FAIL " + t.getKey() + ": " + error.getClass().getSimpleName() + " " + error.getMessage()); }
        if (failures > 0) throw new AssertionError("Codex API-key pool failures=" + failures);
        System.out.println("CodexApiKeyPoolHttpTest PASS checks=" + checks + " importRequests=" + imports);
    }
}
