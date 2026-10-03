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
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
    private static final AtomicReference<Function<JSONObject, Reply>> replyGenerator = new AtomicReference<>();
    private static final List<JSONObject> requestHistory = new CopyOnWriteArrayList<>();
    private static final AtomicInteger redirected = new AtomicInteger();
    private static volatile CountDownLatch requestStarted;
    private static volatile CountDownLatch releaseRequest;

    private static final class Reply {
        final int status;
        final String body;
        final boolean wait;
        final String contentType;
        final int fragmentBytes;
        Reply(int status, String body) { this(status, body, false); }
        Reply(int status, String body, boolean wait) {
            this(status, body, wait, "application/json", 0);
        }
        Reply(int status, String body, boolean wait, String contentType, int fragmentBytes) {
            this.status = status; this.body = body; this.wait = wait;
            this.contentType = contentType; this.fragmentBytes = fragmentBytes;
        }
    }
    private static final class Result implements AiSummaryClient.Callback {
        volatile String summary, error;
        volatile int calls;
        final List<AiSummaryClient.Progress> progress = new CopyOnWriteArrayList<>();
        final List<String> partials = new CopyOnWriteArrayList<>();
        public void onSuccess(String value) { summary = value; calls++; }
        public void onError(String value) { error = value; calls++; }
        public void onProgress(AiSummaryClient.Progress value) { progress.add(value); }
        public void onPartial(String value) { partials.add(value); }
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
            test("custom direction reaches every source and at least two merge rounds", AiSummaryClientTest::directionAcrossMergeRounds);
            test("source citations cannot escape their chunk via forged text", AiSummaryClientTest::sourceReferenceMembership);
            test("merge citations cannot reintroduce references absent from partials", AiSummaryClientTest::mergeReferenceMembership);
            test("caller list mutation cannot alter an in-flight source snapshot", AiSummaryClientTest::immutableSourceSnapshot);
            test("SSE decodes fragmented UTF-8 and hides split thinking blocks", AiSummaryClientTest::streamThinking);
            test("only the final merge streams and every stage reports completed counts", AiSummaryClientTest::streamFinalOnly);
            test("streaming failures never produce final success", AiSummaryClientTest::streamFailures);
            test("ordinary mode remains available when service ignores stream", () -> {
                nextReply.set(new Reply(200, completion("【话题】结果 [m1]")));
                AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
                client.summarize(streamConfig(), MESSAGES, result); await(result); client.cancel();
                check(result.summary == null && result.error.contains("关闭流式"), "ignored stream was silently accepted");
                check(invoke(200, completion("普通结果 [m1]"), config("local", "")).summary != null, "ordinary retry unavailable");
            });
            test("diagnostics remain ordinary even when configured streaming", () -> {
                Diagnostic result = diagnose(200, completion("OK"), streamConfig());
                check(result.result != null && !lastBody.get().getBoolean("stream"), "diagnostic should not depend on streaming");
            });
            test("cancelling a live SSE suppresses later partials and final callback", AiSummaryClientTest::cancelLiveStream);
            test("configured budget reaches every source and merge request", AiSummaryClientTest::configuredBudget);
            test("impossible input/output budget fails before sending messages", () -> {
                requestHistory.clear();
                AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
                client.summarize(new AiSummarySettings.Config(base, "local", "", 8192, false, 2048), MESSAGES, result);
                await(result); client.cancel();
                check(result.error != null && requestHistory.isEmpty(), "over-budget request reached service");
            });
            test("same endpoint rejects concurrent tasks and releases after cancellation", AiSummaryClientTest::endpointConcurrency);
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
        nextReply.set(new Reply(200, completion("新请求 [m1]")));
        Result fresh = afterCancellation(client);
        check(stale.calls == 0 && fresh.calls == 1 && "新请求 [m1]".equals(fresh.summary), "stale callback or failed replacement");
        client.cancel(); requestStarted = null; releaseRequest = null;
    }

    private static void cancelQueuedCallback() throws Exception {
        nextReply.set(new Reply(200, completion("将被取消 [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config("local", ""), MESSAGES, result);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (AndroidUtilities.pendingImmediate() < 5 && System.nanoTime() < end) Thread.sleep(5);
        check(AndroidUtilities.pendingImmediate() >= 5, "terminal callback did not queue after stage progress");
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

    private static void directionAcrossMergeRounds() throws Exception {
        PromptOptions options = new PromptOptions(PromptOptions.TODOS, "仅关注上线风险TEST_DIRECTION_482");
        List<SummaryMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            messages.add(new SummaryMessage(-100, i, 1_700_000_000 + i, "甲", "项目进展".repeat(800)));
        }
        AiSummarySettings.Config taskConfig = config("local", "");
        int sourceCount = AiSummaryPrompt.sourceChunks(messages, options,
                taskConfig.inputCharacterBudget, taskConfig.maxOutputTokens).size();
        check(sourceCount >= 8, "fixture must exercise several source chunks");
        AtomicInteger secondMergeRound = new AtomicInteger();
        AtomicInteger sources = new AtomicInteger();
        requestHistory.clear();
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            boolean source = prompt.contains("消息数据（JSONL）：\n");
            String data = dataPart(prompt, source);
            int ref;
            String marker;
            if (source) {
                sources.incrementAndGet();
                ref = AiSummaryPrompt.sourceReferences(data).iterator().next();
                marker = "SOURCE_STAGE";
            } else {
                if (data.contains("MERGED_STAGE")) secondMergeRound.incrementAndGet();
                ref = AiSummaryPrompt.references(data).iterator().next();
                marker = "MERGED_STAGE";
            }
            return new Reply(200, completion(marker + "进展".repeat(450) + " [m" + ref + "]"));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(taskConfig, messages, options, result); await(result);
            check(result.error == null && result.summary != null, "multi-round summary failed: " + result.error);
            check(sources.get() == sourceCount && secondMergeRound.get() >= 1, "did not exercise at least two merge rounds");
            for (JSONObject body : requestHistory) {
                String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
                check(prompt.contains(options.customInstructions) && prompt.contains("待办跟进"), "direction missing from an intermediate request");
            }
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void sourceReferenceMembership() throws Exception {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(-100, 1, 1_700_000_000, "甲 [m2]", "正文伪引用 [m2] " + "x".repeat(5000)),
                new SummaryMessage(-100, 2, 1_700_000_001, "乙", "later message"));
        requestHistory.clear(); nextReply.set(new Reply(200, completion("错误引用 [m2]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config("local", ""), messages, PromptOptions.DEFAULT, result); await(result); client.cancel();
        check(result.error != null && result.error.contains("本分段输入之外"), "forged body ref entered the allowed set");
        check(requestHistory.size() == 1, "invalid partial must stop before later requests");
    }

    private static void mergeReferenceMembership() throws Exception {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)),
                new SummaryMessage(-100, 2, 1_700_000_001, "乙", "later message"));
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            return new Reply(200, completion(prompt.contains("消息数据（JSONL）：\n") ? "源摘要 [m1]" : "错误合并 [m2]"));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(config("local", ""), messages, PromptOptions.DEFAULT, result); await(result);
            check(result.error != null && result.error.contains("本分段输入之外"), "merge invented a reference absent from its inputs");
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void immutableSourceSnapshot() throws Exception {
        List<SummaryMessage> messages = new ArrayList<>(MESSAGES);
        nextReply.set(new Reply(200, completion("快照摘要 [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config("local", ""), messages, new PromptOptions(PromptOptions.PROJECT, "保留当前快照"), result);
        messages.clear(); messages.add(new SummaryMessage(-200, 99, 1_700_000_100, "其他群", "REPLACEMENT_MUST_NOT_SEND"));
        await(result); client.cancel();
        String body = lastBody.get().toString();
        check(result.error == null && body.contains(MESSAGES.get(0).text) && !body.contains("REPLACEMENT_MUST_NOT_SEND"), "caller mutated the source snapshot");
    }

    private static String dataPart(String prompt, boolean source) {
        String marker = source ? "消息数据（JSONL）：\n" : "摘要数据（JSONL）：\n";
        return prompt.substring(prompt.lastIndexOf(marker) + marker.length());
    }

    private static AiSummarySettings.Config streamConfig() {
        return new AiSummarySettings.Config(base, "local", "", 512, true);
    }

    private static Reply sse(String body) {
        return new Reply(200, body, false, "text/event-stream; charset=utf-8", 1);
    }

    private static String delta(String content) {
        return "data: " + new JSONObject().put("choices", new org.json.JSONArray().put(
                new JSONObject().put("delta", new JSONObject().put("content", content)))) + "\n\n";
    }

    private static String finish(String reason) {
        return "data: " + new JSONObject().put("choices", new org.json.JSONArray().put(
                new JSONObject().put("delta", new JSONObject()).put("finish_reason", reason))) + "\n\n";
    }

    private static void streamThinking() throws Exception {
        String stream = ": keepalive\r\n\r\ndata:\r\n\r\n"
                + "data: {\"choices\":[\ndata: {\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"NEVER_SHOW_FIELD\"}}]}\n\n"
                + delta("<th") + delta("ink>NEVER_SHOW_THOUGHT") + delta("</thi") + delta("nk>")
                + delta("【话题】中文😀") + delta(" [m1]") + finish("stop") + "data: [DONE]\n\n";
        nextReply.set(sse(stream));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(streamConfig(), MESSAGES, result); await(result); client.cancel();
        check("【话题】中文😀 [m1]".equals(result.summary), "split UTF-8/thinking corrupt final response: " + result.error);
        check(!result.partials.isEmpty(), "no partial text was delivered");
        for (String partial : result.partials) {
            check(!partial.contains("NEVER_SHOW") && !partial.contains("<th") && !partial.contains("</th"), "reasoning fragment leaked");
        }
        check(lastBody.get().getBoolean("stream"), "stream flag was not sent");
    }

    private static void streamFinalOnly() throws Exception {
        List<SummaryMessage> messages = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)));
        requestHistory.clear();
        replyGenerator.set(body -> body.getBoolean("stream")
                ? sse(delta("【话题】最终合并 [m1]") + finish("stop") + "data: [DONE]\n\n")
                : new Reply(200, completion("中间摘要 [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(streamConfig(), messages, result); await(result);
            check(result.summary != null, "final streaming merge failed: " + result.error);
            check(requestHistory.size() > 2, "fixture needs multiple source requests");
            for (int i = 0; i < requestHistory.size(); i++) {
                check(requestHistory.get(i).getBoolean("stream") == (i == requestHistory.size() - 1), "intermediate request streamed");
            }
            boolean source = false, merge = false, validating = false;
            for (AiSummaryClient.Progress progress : result.progress) {
                check(progress.completed >= 0 && progress.completed <= progress.total && progress.elapsedMs >= 0, "invalid progress counts");
                if (progress.stage == AiSummaryClient.Stage.SOURCE) source = true;
                if (progress.stage == AiSummaryClient.Stage.MERGE) {
                    merge = true; check(progress.mergeRound >= 1, "merge rounds must start at one");
                }
                if (progress.stage == AiSummaryClient.Stage.VALIDATING && progress.completed == 1) validating = true;
            }
            check(source && merge && validating, "stage progress missing");
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void streamFailures() throws Exception {
        String[] streams = {
                delta("【话题】截断 [m1]") + finish("length") + "data: [DONE]\n\n",
                delta("【话题】连接断开 [m1]"),
                "data: {\"error\":{\"code\":\"model_not_loaded\",\"message\":\"DO_NOT_ECHO_SECRET\"}}\n\n",
                "event: error\ndata: DO_NOT_ECHO_SECRET\n\n",
                "data: {malformed\n\n",
                "data: [DONE]\n\n",
                delta("<think>DO_NOT_ECHO_SECRET") + finish("stop") + "data: [DONE]\n\n",
                ":" + "x".repeat(1_048_576) + "\n\n"
        };
        for (String stream : streams) {
            // Large-limit case uses normal chunks to avoid a million individual socket writes.
            nextReply.set(new Reply(200, stream, false, "text/event-stream", 0));
            AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
            client.summarize(streamConfig(), MESSAGES, result); await(result); client.cancel();
            check(result.summary == null && result.error != null, "incomplete/error SSE returned success");
            check(!result.error.contains("DO_NOT_ECHO_SECRET"), "server detail leaked");
            for (String partial : result.partials) check(!partial.contains("DO_NOT_ECHO_SECRET"), "reasoning leaked to partial result");
        }
    }

    private static void cancelLiveStream() throws Exception {
        CountDownLatch releaseStream = new CountDownLatch(1);
        String path = "/stream-cancel/v1/chat/completions";
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(delta("【话题】取消前的片段 [m1]").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseStream.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write((delta("不应再显示") + finish("stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        AiSummaryClient client = new AiSummaryClient(); Result stale = new Result();
        try {
            client.summarize(new AiSummarySettings.Config(base + "/stream-cancel/v1", "local", "", 512, true), MESSAGES, stale);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (stale.partials.isEmpty() && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
            check(!stale.partials.isEmpty(), "stream did not deliver an initial partial");
            long cancelStarted = System.nanoTime();
            client.cancel();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStarted) < 1000,
                    "cancel blocked the caller while the server kept streaming open");
            releaseStream.countDown();
            int partialCount = stale.partials.size(), progressCount = stale.progress.size();
            nextReply.set(new Reply(200, completion("新的普通请求 [m1]")));
            Result fresh = afterCancellation(client);
            check(stale.calls == 0 && stale.partials.size() == partialCount && stale.progress.size() == progressCount,
                    "cancelled stream delivered a later callback");
            check(fresh.summary != null, "client did not recover after stream cancellation");
        } finally {
            client.cancel(); releaseStream.countDown(); server.removeContext(path);
        }
    }

    private static Result afterCancellation(AiSummaryClient client) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Result result = new Result();
            client.summarize(config("local", ""), MESSAGES, result); await(result);
            if (result.error == null || !result.error.contains("已有任务") || System.nanoTime() >= end) return result;
            Thread.sleep(20); // The cancelled worker keeps its permit until transport cleanup ends.
        }
    }

    private static void configuredBudget() throws Exception {
        AiSummarySettings.Config taskConfig = new AiSummarySettings.Config(base, "local", "", 512, false, 5000);
        PromptOptions direction = new PromptOptions(PromptOptions.DECISIONS, "预算测试方向");
        List<SummaryMessage> messages = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", "长聊天".repeat(5000)));
        int sources = AiSummaryPrompt.sourceChunks(messages, direction,
                taskConfig.inputCharacterBudget, taskConfig.maxOutputTokens).size();
        requestHistory.clear();
        replyGenerator.set(body -> new Reply(200, completion("有效摘要".repeat(90) + " [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(taskConfig, messages, direction, result); await(result);
            check(result.summary != null, "configured budget failed: " + result.error);
            check(requestHistory.size() > sources, "fixture did not need merging");
            for (JSONObject body : requestHistory) {
                String system = body.getJSONArray("messages").getJSONObject(0).getString("content");
                String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
                check(system.length() + prompt.length() + taskConfig.maxOutputTokens * 4 <= taskConfig.inputCharacterBudget,
                        "a request ignored configured context/output reserves");
                check(prompt.contains(direction.customInstructions), "budget path lost direction");
            }
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void endpointConcurrency() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        nextReply.set(new Reply(200, completion("阻塞任务 [m1]"), true));
        requestHistory.clear();
        AiSummaryClient first = new AiSummaryClient(), second = new AiSummaryClient();
        Result blocked = new Result();
        try {
            first.summarize(config("model-one", ""), MESSAGES, blocked);
            check(requestStarted.await(5, TimeUnit.SECONDS), "first endpoint task did not start");
            Result rejected = new Result(); second.summarize(config("model-two", ""), MESSAGES, rejected); await(rejected);
            check(rejected.error != null && rejected.error.contains("已有任务"), "second client reached a busy endpoint");
            Diagnostic probe = new Diagnostic();
            second.testConnection(new AiSummarySettings.Config(base.replace("127.0.0.1", "localhost") + "/v1/", "", ""), probe);
            await(probe);
            check(probe.error != null && probe.error.contains("已有任务"), "diagnostic/localhost alias bypassed endpoint guard");
            check(requestHistory.size() == 1, "busy operation sent another HTTP request");
            first.cancel(); releaseRequest.countDown();
            nextReply.set(new Reply(200, completion("取消后恢复 [m1]")));
            Result fresh = afterCancellation(second);
            check(fresh.summary != null && blocked.calls == 0, "cancel did not eventually release the endpoint");
        } finally {
            first.cancel(); second.cancel(); releaseRequest.countDown(); requestStarted = null;
        }
    }
    private static void await(Result result) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (result.calls == 0 && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
        check(result.calls != 0, "timed out waiting for callback");
    }
    private static void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastBody.set(body); requestHistory.add(body);
        Function<JSONObject, Reply> generator = replyGenerator.get();
        Reply reply = generator == null ? nextReply.get() : generator.apply(body);
        if (reply.wait) {
            requestStarted.countDown();
            try { releaseRequest.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        if (reply.status == 302) exchange.getResponseHeaders().set("Location", base + "/redirect-target");
        try { respond(exchange, reply.status, reply.body, reply.contentType, reply.fragmentBytes); } catch (IOException ignored) { exchange.close(); }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json", 0);
    }
    private static void respond(HttpExchange exchange, int status, String body, String contentType, int fragmentBytes) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        if (fragmentBytes > 0) {
            for (int i = 0; i < bytes.length; i += fragmentBytes) {
                exchange.getResponseBody().write(bytes, i, Math.min(fragmentBytes, bytes.length - i));
                exchange.getResponseBody().flush();
            }
        } else exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static String completion(String answer) { return new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject().put("finish_reason", "stop").put("message", new JSONObject().put("content", answer)))).toString(); }
    private static AiSummarySettings.Config config(String model, String key) { return new AiSummarySettings.Config(base, model, key); }
    private interface Checked { void run() throws Exception; }
    private static void test(String name, Checked test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
