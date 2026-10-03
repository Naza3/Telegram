package org.telegram.messenger.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP against production transport, using only a fake Android callback looper. */
public final class AiSummaryClientTest {
    private static final List<SummaryMessage> MESSAGES = List.of(
            new SummaryMessage(-100, 51, 1_700_000_000, "甲", "明天十点讨论发布计划。"));
    private static int passed;
    private static HttpServer server;
    private static String base;
    private static final AtomicReference<Reply> nextReply = new AtomicReference<>();
    private static final AtomicReference<JSONObject> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicInteger redirected = new AtomicInteger();
    private static volatile CountDownLatch requestStarted;
    private static volatile CountDownLatch releaseRequest;

    private static final class Reply {
        final int status;
        final String body;
        final boolean wait;
        Reply(int status, String body) { this(status, body, false); }
        Reply(int status, String body, boolean wait) {
            this.status = status; this.body = body; this.wait = wait;
        }
    }
    private static final class Result implements AiSummaryClient.Callback {
        volatile String summary, error;
        volatile int calls;
        public void onSuccess(String value) { summary = value; calls++; }
        public void onError(String value) { error = value; calls++; }
    }
    private static final class Diagnostic implements AiSummaryClient.DiagnosticCallback {
        volatile AiSummaryClient.DiagnosticResult result;
        volatile String error;
        volatile int calls;
        public void onSuccess(AiSummaryClient.DiagnosticResult value) { result = value; calls++; }
        public void onError(String value) { error = value; calls++; }
    }

    public static void main(String[] args) throws Exception {
        int exit = 0;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(action -> {
                Thread thread = new Thread(action, "summary-http-test"); thread.setDaemon(true); return thread;
            }));
            server.createContext("/v1/chat/completions", AiSummaryClientTest::handle);
            server.createContext("/redirect-target", exchange -> {
                redirected.incrementAndGet(); respond(exchange, 200, completion("不应读取 [m1]"));
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();

            test("non-streaming POST, original text, bearer and model", () -> {
                Result result = invoke(200, completion("发布讨论定于明天十点 [m1]"), config("local-model", "test-session-secret"));
                check(result.error == null && result.summary.contains("[m1]"), "expected summary");
                JSONObject body = lastBody.get();
                check("POST".equals(lastMethod.get()), "must POST");
                check(body.has("stream") && !body.getBoolean("stream"), "stream must be false");
                check("local-model".equals(body.getString("model")), "model was not forwarded");
                check(body.getJSONArray("messages").getJSONObject(1).getString("content").contains(MESSAGES.get(0).text), "original source text absent");
                check("Bearer test-session-secret".equals(lastAuthorization.get()), "bearer missing");
            });
            test("empty model and key omitted for local MNN service", () -> {
                Result result = invoke(200, completion("摘要 [m1]"), config("", ""));
                check(result.summary != null, "empty model rejected");
                check(!lastBody.get().has("model"), "empty model should be omitted");
                check(lastAuthorization.get() == null, "empty key produced authorization");
            });
            test("401 response is actionable and does not echo server body/key", () -> {
                Result result = invoke(401, "test-session-secret private diagnostic", config("local", "test-session-secret"));
                check(result.error != null && result.error.contains("401"), "401 not identified");
                check(!result.error.contains("test-session-secret"), "server detail leaked");
            });
            test("redirect is rejected without contacting target", () -> {
                Result result = invoke(302, "redirect", config("local", "secret"));
                check(result.error != null && result.error.contains("重定向"), "redirect not identified");
                check(redirected.get() == 0, "authorization redirect target contacted");
            });
            test("non-JSON success is rejected", () -> check(invoke(200, "<html>failure</html>", config("local", "")).error != null, "accepted HTML"));
            test("missing choices is rejected", () -> check(invoke(200, "{}", config("local", "")).error != null, "accepted missing choices"));
            test("empty choices is rejected", () -> check(invoke(200, "{\"choices\":[]}", config("local", "")).error != null, "accepted empty choices"));
            test("null content is rejected", () -> check(invoke(200, "{\"choices\":[{\"message\":{\"content\":null}}]}", config("local", "")).error != null, "accepted null content"));
            test("structured text content supported", () -> {
                Result result = invoke(200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"摘要 [m1]\"}]}}]}", config("local", ""));
                check("摘要 [m1]".equals(result.summary), "text part not extracted");
            });
            test("truncated completion rejected", () -> check(invoke(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"不完整 [m1]\"}}]}", config("local", "")).error != null, "accepted truncated answer"));
            test("MNN HTTP 200 Error content is rejected", () -> check(invoke(200, completion("Error: model not loaded [m1]"), config("local", "")).error != null, "MNN failure displayed as summary"));
            test("reasoning removed before rendering", () -> {
                Result result = invoke(200, completion("<think>private reasoning</think>可显示摘要 [m1]"), config("local", ""));
                check("可显示摘要 [m1]".equals(result.summary), "reasoning exposed");
            });
            test("over-limit response rejected", () -> check(invoke(200, "x".repeat(1_048_577), config("local", "")).error != null, "accepted over-limit response"));
            test("cancel suppresses in-flight completion and permits a later request", AiSummaryClientTest::cancelInFlight);
            test("cancel suppresses callback already queued on main thread", AiSummaryClientTest::cancelQueuedCallback);
            test("configuration URLs and secret-store boundary", AiSummaryClientTest::settings);
            test("diagnostic sends no chat and reports only response model", () -> {
                JSONObject reply = new JSONObject(completion("OK")).put("model", "server-loaded-model");
                Diagnostic result = diagnose(200, reply.toString(), config("requested-alias", "diagnostic-secret"));
                check(result.error == null && result.result.elapsedMs >= 0, "expected diagnostic success");
                check("server-loaded-model".equals(result.result.reportedModel), "request model confused with reported model");
                JSONObject body = lastBody.get();
                check(!body.getBoolean("stream") && body.getInt("max_tokens") == 64, "probe must be short and non-streaming");
                check(body.getJSONArray("messages").length() == 2, "unexpected test message count");
                check("Reply OK.".equals(body.getJSONArray("messages").getJSONObject(1).getString("content")), "probe is not fixed text");
                check(!body.toString().contains(MESSAGES.get(0).text), "chat content leaked into probe");
            });
            test("diagnostic model remains unknown when response omits it", () -> {
                Diagnostic result = diagnose(200, completion("OK"), config("configured-only", ""));
                check(result.result != null && result.result.reportedModel == null, "reported model inferred from config");
            });
            test("diagnostic ignores unsafe reported model metadata", () -> {
                for (String model : List.of("x".repeat(129), "bad\nmodel", "echo-diagnostic-secret")) {
                    Diagnostic result = diagnose(200, new JSONObject(completion("OK")).put("model", model).toString(), config("local", "diagnostic-secret"));
                    check(result.result != null && result.result.reportedModel == null, "unsafe metadata displayed");
                }
            });
            test("diagnostic differentiates auth, model and context without echoing payload", () -> {
                for (int status : List.of(401, 403)) {
                    Diagnostic result = diagnose(status, "diagnostic-secret", config("local", "diagnostic-secret"));
                    check(result.error.contains(Integer.toString(status)) && !result.error.contains("diagnostic-secret"), "auth error lost or leaked");
                }
                Diagnostic model = diagnose(200, completion("Error: model not loaded diagnostic-secret"), config("local", "diagnostic-secret"));
                check(model.error.contains("模型尚未加载") && !model.error.contains("diagnostic-secret"), "model state not diagnosed safely");
                Diagnostic context = diagnose(400, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"diagnostic-secret\"}}", config("local", "diagnostic-secret"));
                check(context.error.contains("上下文长度") && !context.error.contains("diagnostic-secret"), "context error not diagnosed safely");
                Diagnostic generic = diagnose(200, completion("Error: diagnostic-secret internal engine problem"), config("local", "diagnostic-secret"));
                check(generic.error.contains("HTTP 200 Error") && !generic.error.contains("diagnostic-secret"), "200 error not diagnosed safely");
            });
            test("diagnostic rejects unsupported response formats", () -> {
                check(diagnose(200, "<html>diagnostic-secret</html>", config("local", "diagnostic-secret")).error.contains("格式"), "HTML accepted");
                check(diagnose(200, "{\"choices\":[]}", config("local", "")).error.contains("choices"), "empty choices accepted");
                check(diagnose(200, "{\"error\":{\"code\":\"model_not_loaded\"}}", config("local", "")).error.contains("模型尚未加载"), "200 error object accepted");
            });
            test("diagnostic identifies a stopped service", () -> {
                int port;
                try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
                AiSummaryClient client = new AiSummaryClient(1000, 1000, 1000);
                Diagnostic result = new Diagnostic();
                client.testConnection(new AiSummarySettings.Config("http://127.0.0.1:" + port, "", ""), result);
                await(result); client.cancel();
                check(result.error.contains("未启动") || result.error.contains("拒绝连接"), "connection refusal not identified");
            });
            test("diagnostic read timeout is actionable", AiSummaryClientTest::diagnosticTimeout);
            test("diagnostic cancellation suppresses an already queued callback", AiSummaryClientTest::cancelDiagnostic);
            System.out.println("AiSummaryClientTest: " + passed + " passed");
        } catch (Throwable error) {
            error.printStackTrace(); exit = 1;
        } finally {
            if (releaseRequest != null) releaseRequest.countDown();
            if (server != null) server.stop(0);
        }
        // Production uses an application-lifetime executor; this harness owns its process.
        System.exit(exit);
    }

    private static void settings() {
        check((base + "/v1/chat/completions").equals(AiSummarySettings.completionUrl(config("", ""))), "root URL normalization");
        check((base + "/v1/chat/completions").equals(AiSummarySettings.completionUrl(new AiSummarySettings.Config(base + "/v1/", "", ""))), "v1 normalization");
        check((base + "/v1/chat/completions").equals(AiSummarySettings.completionUrl(new AiSummarySettings.Config(base + "/v1/chat/completions", "", ""))), "complete URL duplicated");
        for (String invalid : List.of("ftp://localhost/a", "http://user:password@localhost/v1", "http://localhost/v1?key=secret", "http://localhost/v1#fragment", "http://localhost:0", "http://localhost:65536")) {
            check(AiSummarySettings.validate(new AiSummarySettings.Config(invalid, "", "")) != null, "accepted invalid URL: " + invalid);
        }
        check(AiSummarySettings.validate(config("", "bad\r\nHeader: value")) != null, "accepted header injection");
        AiSummarySettings.save(0, config("", "only-in-memory"));
        check(!MessagesController.getMainSettings(0).values.containsValue("only-in-memory"), "key written to preferences");
        check("only-in-memory".equals(AiSummarySettings.load(0).apiKey), "session key unavailable");
    }

    private static void cancelInFlight() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        nextReply.set(new Reply(200, completion("旧请求 [m1]"), true));
        AiSummaryClient client = new AiSummaryClient(); Result stale = new Result();
        client.summarize(config("local", ""), MESSAGES, stale);
        check(requestStarted.await(5, TimeUnit.SECONDS), "request did not begin");
        client.cancel(); releaseRequest.countDown();
        Result fresh = new Result(); nextReply.set(new Reply(200, completion("新请求 [m1]")));
        client.summarize(config("local", ""), MESSAGES, fresh); await(fresh);
        check(stale.calls == 0 && fresh.calls == 1 && "新请求 [m1]".equals(fresh.summary), "stale callback or failed replacement");
        client.cancel(); requestStarted = null; releaseRequest = null;
    }

    private static void cancelQueuedCallback() throws Exception {
        nextReply.set(new Reply(200, completion("将被取消 [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config("local", ""), MESSAGES, result);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (AndroidUtilities.pendingImmediate() == 0 && System.nanoTime() < end) Thread.sleep(5);
        check(AndroidUtilities.pendingImmediate() > 0, "callback did not queue");
        client.cancel(); AndroidUtilities.drain();
        check(result.calls == 0, "cancelled queued callback delivered");
    }

    private static Result invoke(int status, String body, AiSummarySettings.Config config) throws Exception {
        nextReply.set(new Reply(status, body));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config, MESSAGES, result); await(result); client.cancel();
        check(result.calls == 1, "expected exactly one terminal callback"); return result;
    }
    private static Diagnostic diagnose(int status, String body, AiSummarySettings.Config config) throws Exception {
        nextReply.set(new Reply(status, body));
        AiSummaryClient client = new AiSummaryClient(); Diagnostic result = new Diagnostic();
        client.testConnection(config, result); await(result); client.cancel();
        check(result.calls == 1, "expected exactly one diagnostic callback"); return result;
    }
    private static void await(Diagnostic result) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (result.calls == 0 && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
        check(result.calls != 0, "timed out waiting for diagnostic callback");
    }
    private static void diagnosticTimeout() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        nextReply.set(new Reply(200, completion("OK"), true));
        AiSummaryClient client = new AiSummaryClient(1000, 1000, 50);
        Diagnostic result = new Diagnostic();
        try {
            client.testConnection(config("local", ""), result);
            check(requestStarted.await(5, TimeUnit.SECONDS), "timeout probe did not start");
            await(result);
            check(result.error != null && result.error.contains("超时"), "timeout not identified");
        } finally {
            client.cancel(); releaseRequest.countDown(); requestStarted = null;
            // The handler already captured the latch before releasing it.
        }
    }
    private static void cancelDiagnostic() throws Exception {
        nextReply.set(new Reply(200, completion("OK")));
        AiSummaryClient client = new AiSummaryClient(); Diagnostic result = new Diagnostic();
        client.testConnection(config("local", ""), result);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (AndroidUtilities.pendingImmediate() == 0 && System.nanoTime() < end) Thread.sleep(5);
        check(AndroidUtilities.pendingImmediate() > 0, "diagnostic callback did not queue");
        client.cancel(); AndroidUtilities.drain();
        check(result.calls == 0, "cancelled diagnostic callback delivered");
    }
    private static void await(Result result) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (result.calls == 0 && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
        check(result.calls != 0, "timed out waiting for callback");
    }
    private static void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = nextReply.get();
        if (reply.wait) {
            requestStarted.countDown();
            try { releaseRequest.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        if (reply.status == 302) exchange.getResponseHeaders().set("Location", base + "/redirect-target");
        try { respond(exchange, reply.status, reply.body); } catch (IOException ignored) { exchange.close(); }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static String completion(String answer) { return new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject().put("finish_reason", "stop").put("message", new JSONObject().put("content", answer)))).toString(); }
    private static AiSummarySettings.Config config(String model, String key) { return new AiSummarySettings.Config(base, model, key); }
    private interface Checked { void run() throws Exception; }
    private static void test(String name, Checked test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
