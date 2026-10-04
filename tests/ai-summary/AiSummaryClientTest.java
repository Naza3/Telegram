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
    private static final PromptOptions TEST_PROMPT = new PromptOptions(PromptOptions.GENERAL, "测试要求：概述聊天中的事实。");
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
        final List<AiSummaryClient.RequestStatus> statuses = new CopyOnWriteArrayList<>();
        final List<AiSummaryClient.RequestInput> inputs = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        public void onSuccess(String value) { events.add("SUCCESS"); summary = value; calls++; }
        public void onError(String value) { events.add("ERROR"); error = value; calls++; }
        public void onProgress(AiSummaryClient.Progress value) { progress.add(value); }
        public void onPartial(String value) { events.add("PARTIAL:" + value); partials.add(value); }
        public void onRequestStatus(AiSummaryClient.RequestStatus value) { events.add(value.phase.name()); statuses.add(value); }
        public void onRequestInput(AiSummaryClient.RequestInput value) { events.add("INPUT"); inputs.add(value); }
    }
    private static final class Diagnostic implements AiSummaryClient.DiagnosticCallback {
        volatile AiSummaryClient.DiagnosticResult result;
        volatile AiSummaryClient.DiagnosticErrorInfo info;
        volatile String error;
        volatile int calls;
        public void onSuccess(AiSummaryClient.DiagnosticResult value) { result = value; calls++; }
        public void onError(String value) { error = value; calls++; }
        public void onError(String value, AiSummaryClient.DiagnosticErrorInfo detail) { info = detail; onError(value); }
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

            test("blank manual core stops before HTTP and permits a later configured request", AiSummaryClientTest::blankManualCore);
            test("non-streaming POST, original text, bearer and model", () -> {
                Result result = invoke(200, completion("发布讨论定于明天十点"), config("local-model", "test-session-secret"));
                check(result.error == null && "发布讨论定于明天十点".equals(result.summary), "citation-free summary rejected");
                JSONObject body = lastBody.get();
                check("POST".equals(lastMethod.get()), "must POST");
                check(body.has("stream") && !body.getBoolean("stream"), "stream must be false");
                check("local-model".equals(body.getString("model")), "model was not forwarded");
                check(TEST_PROMPT.customInstructions.equals(body.getJSONArray("messages").getJSONObject(0).getString("content")), "manual system text changed");
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
            test("ordinary and question completions reject unfinished reasoning tags", AiSummaryClientTest::ordinaryThinkingFailures);
            test("ordinary completions hide attributed and nested reasoning", () -> {
                String content = "<THINK mode=analysis>INTERNAL_REASONING <think>nested [m1]</think> still hidden</THINK>【回答】明天十点 [m1]";
                Result result = askReply(completion(content), MESSAGES, "几点？", List.of());
                check("【回答】明天十点 [m1]".equals(result.summary), "nested or attributed reasoning exposed");
                check("【回答】明天十点 [m1]".equals(invoke(200, completion(content), config("local", "")).summary),
                        "summary and question reasoning policies differ");
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
                check(!body.getBoolean("stream") && body.getInt("max_tokens") == 512, "probe must use configured output limit and remain non-streaming");
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
            test("diagnostic sends the configured 64, 512, 1024 or 2048 output limit", AiSummaryClientTest::diagnosticConfiguredOutputLimit);
            test("diagnostic length termination reports its configured limit without accepting or exposing content", AiSummaryClientTest::diagnosticOutputLimitFailure);
            test("summary and question length termination retain incomplete-result errors", AiSummaryClientTest::chatOutputLimitFailure);
            test("diagnostic cancellation suppresses an already queued callback", AiSummaryClientTest::cancelDiagnostic);
            test("diagnostic HTTP errors expose only selected safe explanations", AiSummaryClientTest::diagnosticHttpDetails);
            test("diagnostic errors hide HTML, opaque JSON and payload echoes", AiSummaryClientTest::diagnosticHttpUnknownFormats);
            test("diagnostic reasons redact encoded credentials before display limits", AiSummaryClientTest::diagnosticHttpRedaction);
            test("detailed HTTP failures remain private to the fixed-text diagnostic", AiSummaryClientTest::diagnosticHttpIsolation);
            test("cancellation suppresses queued detailed diagnostic errors", AiSummaryClientTest::cancelDetailedDiagnostic);
            test("actual MNN local API contract accepts every request path without unsupported sampling options", AiSummaryClientTest::mnnLocalApiContract);
            test("MNN output boundary is explained without classifying every HTTP 400 as an output error", AiSummaryClientTest::mnnOutputLimitContract);
            test("explicit MNN service profile rejects incompatible output before any network request", () -> {
                int before = requestHistory.size();
                for (int tokens : List.of(4096, 8192)) {
                    AiSummarySettings.Config incompatible = new AiSummarySettings.Config(base + "/v1", "local", "", tokens,
                            true, 64000, AiSummarySettings.ServiceType.MNN_LOCAL);
                    AiSummaryClient client = new AiSummaryClient();
                    Result result = new Result();
                    client.summarize(incompatible, MESSAGES, TEST_PROMPT, result);
                    await(result); client.cancel();
                    check(result.summary == null && result.error.contains("2048") && result.inputs.isEmpty(),
                            "explicit MNN output mismatch must fail before encoding request input");
                    Diagnostic probe = new Diagnostic();
                    client.testConnection(incompatible, probe); await(probe); client.cancel();
                    check(probe.result == null && probe.error.contains("2048"), "probe must apply the same MNN contract");
                }
                check(requestHistory.size() == before, "an incompatible MNN profile reached the model service");
            });
            test("custom direction reaches every source and at least two merge rounds", AiSummaryClientTest::directionAcrossMergeRounds);
            test("direct summaries need no source citations", AiSummaryClientTest::sourceWithoutReferences);
            test("direct merge summaries need no source citations", AiSummaryClientTest::mergeWithoutReferences);
            test("caller list mutation cannot alter an in-flight source snapshot", AiSummaryClientTest::immutableSourceSnapshot);
            test("SSE decodes fragmented UTF-8 and hides split thinking blocks", AiSummaryClientTest::streamThinking);
            test("every source and merge request displays its own safe draft and exact input", AiSummaryClientTest::streamEveryStage);
            test("ordinary request reports sending, response and safe content counters", AiSummaryClientTest::ordinaryRequestStatus);
            test("streaming status distinguishes headers and hidden reasoning from an answer", AiSummaryClientTest::streamRequestStatus);
            test("ordinary and streaming length errors report the actual cap and observed reasoning", AiSummaryClientTest::observedOutputLimits);
            test("stream errors preserve the final safe draft before failing without retry", AiSummaryClientTest::streamFailureTails);
            test("streaming failures never produce final success", AiSummaryClientTest::streamFailures);
            test("ordinary mode remains available when service ignores stream", () -> {
                nextReply.set(new Reply(200, completion("【话题】结果 [m1]")));
                AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
                client.summarize(streamConfig(), MESSAGES, TEST_PROMPT, result); await(result); client.cancel();
                check(result.summary == null && result.error.contains("关闭流式"), "ignored stream was silently accepted");
                check(invoke(200, completion("普通结果 [m1]"), config("local", "")).summary != null, "ordinary retry unavailable");
            });
            test("diagnostics remain ordinary even when configured streaming", () -> {
                Diagnostic result = diagnose(200, completion("OK"), streamConfig());
                check(result.result != null && !lastBody.get().getBoolean("stream"), "diagnostic should not depend on streaming");
            });
            test("cancelling a live SSE suppresses later partials and final callback", AiSummaryClientTest::cancelLiveStream);
            test("cancellation suppresses a queued sensitive request input", AiSummaryClientTest::cancelQueuedRequestInput);
            test("configured budget reaches every source and merge request", AiSummaryClientTest::configuredBudget);
            test("64k budget carries a complete large Chinese request through expanded MNN limits", AiSummaryClientTest::expandedMnnBudget);
            test("impossible input/output budget fails before sending messages", () -> {
                requestHistory.clear();
                AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
                client.summarize(new AiSummarySettings.Config(base, "local", "", 8192, false, 2048), MESSAGES, TEST_PROMPT, result);
                await(result); client.cancel();
                check(result.error != null && requestHistory.isEmpty(), "over-budget request reached service");
            });
            test("same endpoint rejects concurrent tasks and releases after cancellation", AiSummaryClientTest::endpointConcurrency);
            test("question and completed history use immutable source snapshots", AiSummaryClientTest::questionSnapshot);
            test("question, history and direction survive every extraction and merge round", AiSummaryClientTest::questionAcrossMergeRounds);
            test("question history cannot authorize a citation outside this source chunk", AiSummaryClientTest::questionSourceMembership);
            test("question merge cannot invent citations when every partial lacks evidence", AiSummaryClientTest::questionMergeMembership);
            test("only the exact insufficient-evidence sentinel may omit citations", AiSummaryClientTest::questionInsufficientEvidence);
            test("question limits fail before HTTP without dropping history", AiSummaryClientTest::questionLimits);
            test("questions stream final answers and still hide reasoning", AiSummaryClientTest::questionStreaming);
            test("questions keep intermediate extraction drafts hidden", AiSummaryClientTest::questionIntermediateDrafts);
            test("cancelled questions do not add turns or deliver stale answers", AiSummaryClientTest::cancelQuestion);
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
        client.summarize(config("local", ""), MESSAGES, TEST_PROMPT, stale);
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
        client.summarize(config("local", ""), MESSAGES, TEST_PROMPT, result);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        java.lang.reflect.Field taskField = AiSummaryClient.class.getDeclaredField("task");
        taskField.setAccessible(true);
        boolean finished = false;
        while (System.nanoTime() < end) {
            synchronized (client) {
                java.util.concurrent.Future<?> task = (java.util.concurrent.Future<?>) taskField.get(client);
                finished = task == null || task.isDone();
            }
            if (finished) break;
            Thread.sleep(5);
        }
        check(finished && AndroidUtilities.pendingImmediate() > 0, "terminal callback did not queue");
        client.cancel(); AndroidUtilities.drain();
        check(result.calls == 0 && result.statuses.isEmpty(), "cancelled queued callback delivered");
    }

    private static void blankManualCore() throws Exception {
        AiSummaryClient client = new AiSummaryClient();
        requestHistory.clear();
        nextReply.set(new Reply(200, completion("已使用手动要求")));
        try {
            PromptOptions[] emptyOptions = {null, PromptOptions.DEFAULT,
                    new PromptOptions(PromptOptions.TODOS, " \n\t ")};
            for (int i = 0; i <= emptyOptions.length; i++) {
                Result blocked = new Result();
                if (i == emptyOptions.length) client.summarize(config("local", ""), MESSAGES, blocked);
                else client.summarize(config("local", ""), MESSAGES, emptyOptions[i], blocked);
                await(blocked);
                check(blocked.calls == 1 && blocked.summary == null
                        && "请先填写核心总结要求。".equals(blocked.error), "blank core did not report the required field");
                check(blocked.inputs.isEmpty() && blocked.statuses.isEmpty() && requestHistory.isEmpty(),
                        "blank core reached request input or HTTP");
            }
            Result fresh = new Result();
            client.summarize(config("local", ""), MESSAGES, TEST_PROMPT, fresh);
            await(fresh);
            check("已使用手动要求".equals(fresh.summary) && fresh.error == null,
                    "client did not recover after entering a manual core");
            check(requestHistory.size() == 1 && fresh.inputs.size() == 1,
                    "manual retry sent an unexpected number of requests");
        } finally {
            client.cancel();
        }
    }

    private static Result invoke(int status, String body, AiSummarySettings.Config config) throws Exception {
        nextReply.set(new Reply(status, body));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config, MESSAGES, TEST_PROMPT, result); await(result); client.cancel();
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
    private static void diagnosticHttpDetails() throws Exception {
        for (String response : List.of(
                "{\"error\":{\"message\":\"Cannot parse messages: content must be a string\",\"request\":\"DO_NOT_ECHO_REQUEST\"}}",
                "{\"error\":\"Cannot parse messages: content must be a string\"}",
                "{\"message\":\"Cannot parse messages: content must be a string\"}",
                "{\"detail\":\"Cannot parse messages: content must be a string\"}")) {
            Diagnostic result = diagnose(400, response, config("local", ""));
            check(result.result == null && result.error.contains("400"), "friendly HTTP status missing");
            check(result.info != null && result.info.status == 400 && "json".equals(result.info.responseFormat), "structured diagnostic missing");
            check("Cannot parse messages: content must be a string".equals(result.info.serverMessage), "wrong JSON field exposed");
        }
        Diagnostic list = diagnose(422, "{\"detail\":[{\"msg\":\"Field required\",\"input\":\"DO_NOT_ECHO_INPUT\",\"loc\":[\"body\",\"messages\"]},{\"msg\":\"Invalid role\"}]}", config("local", ""));
        check(list.info.status == 422 && "Field required；Invalid role".equals(list.info.serverMessage), "422 exposed more than selected msg values");
        Diagnostic plain = diagnose(400, "Bad Request: failed to convert request body", config("local", ""));
        check("text".equals(plain.info.responseFormat) && plain.info.serverMessage.contains("failed to convert"), "short plain explanation suppressed");
        Diagnostic normalized = diagnose(400, new JSONObject().put("message", "Bad\r\nrequest\u0000\u202einvalid\trole").toString(), config("local", ""));
        check("Bad request invalid role".equals(normalized.info.serverMessage), "control/bidi characters not normalized");
    }

    private static void diagnosticHttpUnknownFormats() throws Exception {
        Diagnostic empty = diagnose(400, "", config("local", ""));
        check(empty.info != null && "empty".equals(empty.info.responseFormat) && empty.info.serverMessage.isEmpty(), "empty body fabricated an explanation");
        Diagnostic html = diagnose(400, "<!DOCTYPE html><html>DO_NOT_ECHO_HTML<script>secret</script></html>", config("local", ""));
        check("html".equals(html.info.responseFormat) && !html.info.serverMessage.contains("<") && !html.info.serverMessage.contains("DO_NOT_ECHO"), "HTML body was displayed");
        Diagnostic prefixedHtml = diagnose(400, "Proxy response: <html><body>DO_NOT_ECHO_HTML</body></html>", config("local", ""));
        check("html".equals(prefixedHtml.info.responseFormat) && !prefixedHtml.info.serverMessage.contains("DO_NOT_ECHO"), "prefixed HTML displayed");
        for (String raw : List.of("{\"request\":{\"messages\":[\"DO_NOT_ECHO_OPAQUE\"]}}", "{\"error\":{\"request\":\"DO_NOT_ECHO_OPAQUE\"}}",
                "[\"DO_NOT_ECHO_OPAQUE\"]", "{\"message\":\"truncated DO_NOT_ECHO_OPAQUE")) {
            Diagnostic opaque = diagnose(400, raw, config("local", ""));
            check("json".equals(opaque.info.responseFormat) && opaque.info.serverMessage.isEmpty(), "unknown JSON fell back to a raw dump");
        }
        Diagnostic embedded = diagnose(400, new JSONObject().put("error", "Invalid body. Request body: {\"messages\":[\"DO_NOT_ECHO_PAYLOAD\"]}").toString(), config("local", ""));
        check("Invalid body.".equals(embedded.info.serverMessage), "scalar field exposed an embedded request");
        Diagnostic oversized = diagnose(400, "DO_NOT_ECHO_LONG_BODY".repeat(900), config("local", ""));
        check(oversized.info.serverMessage.isEmpty(), "long unstructured body displayed");
    }

    private static void diagnosticHttpRedaction() throws Exception {
        String key = "s3cr3t/a+b?VALUE";
        StringBuilder unicode = new StringBuilder();
        StringBuilder percent = new StringBuilder();
        for (char value : key.toCharArray()) {
            unicode.append(String.format("\\u%04x", (int) value));
            percent.append(String.format("%%%02X", (int) value));
        }
        for (String encoded : List.of(key, key.replace("/", "\\/"), unicode.toString(), percent.toString(),
                percent.toString().replace("%", "%25"), key.substring(0, 5) + "\u202e" + key.substring(5))) {
            Diagnostic result = diagnose(400, new JSONObject().put("message", "Unsupported option " + encoded + "; use a string").toString(), config("local", key));
            check(result.info.serverMessage.contains("凭据已隐藏") && !result.info.serverMessage.contains("s3cr3t")
                    && !result.info.serverMessage.contains("VALUE") && !result.info.serverMessage.contains("%73")
                    && !result.info.serverMessage.contains("\\u0073"), "known/encoded key not redacted");
        }
        String spacedKey = "SENSITIVE KEY /+?";
        Diagnostic form = diagnose(400, new JSONObject().put("message", "Invalid " + java.net.URLEncoder.encode(spacedKey, "UTF-8")).toString(), config("local", spacedKey));
        check(!form.info.serverMessage.contains("SENSITIVE") && form.info.serverMessage.contains("凭据已隐藏"), "form-encoded space key exposed");
        String formattedKey = "SENSITIVE\u202eKEY";
        Diagnostic format = diagnose(400, new JSONObject().put("message", "Invalid SENSITIVEKEY").toString(), config("local", formattedKey));
        check(!format.info.serverMessage.contains("SENSITIVE") && format.info.serverMessage.contains("凭据已隐藏"), "normalized configured key exposed");
        String generic = "Invalid Authorization: Bearer OTHER_CREDENTIAL; api_key=SECOND_SECRET; password=THIRD_SECRET; token=FOURTH_SECRET; sk-abcdefghijklm";
        Diagnostic credentials = diagnose(400, new JSONObject().put("message", generic).toString(), config("local", ""));
        for (String forbidden : List.of("OTHER_CREDENTIAL", "SECOND_SECRET", "THIRD_SECRET", "FOURTH_SECRET", "sk-abcdefghijklm")) {
            check(!credentials.info.serverMessage.contains(forbidden), "credential-shaped value exposed");
        }
        for (String sensitive : List.of("Cannot reach http://alice:URL_PRIVATE_PASSWORD@proxy.invalid/v1",
                "Rejected Cookie: session=COOKIE_PRIVATE_VALUE; other=COOKIE_SECOND_VALUE")) {
            Diagnostic reason = diagnose(400, new JSONObject().put("message", sensitive).toString(), config("local", ""));
            check(!reason.info.serverMessage.contains("PRIVATE") && !reason.info.serverMessage.contains("COOKIE_SECOND_VALUE"), "URL or Cookie credential exposed");
        }
        Diagnostic capped = diagnose(400, new JSONObject().put("message", "😀".repeat(390) + key + " tail".repeat(90)).toString(), config("local", key));
        String safe = capped.info.serverMessage;
        check(safe.codePointCount(0, safe.length()) <= 400 && !safe.contains("s3cr3t") && safe.endsWith("…"), "key truncated before redaction or code-point cap failed");
    }

    private static void diagnosticHttpIsolation() throws Exception {
        String body = "{\"error\":{\"message\":\"PRIVATE_SERVER_DETAIL\"}}";
        Result summary = invoke(400, body, config("local", ""));
        check(summary.error != null && !summary.error.contains("PRIVATE_SERVER_DETAIL"), "summary error body exposed");
        nextReply.set(new Reply(422, body));
        AiSummaryClient client = new AiSummaryClient(); Result question = new Result();
        client.ask(config("local", ""), MESSAGES, PromptOptions.DEFAULT, "几点？", List.of(), question); await(question); client.cancel();
        check(question.error != null && !question.error.contains("PRIVATE_SERVER_DETAIL"), "question error body exposed");
        for (int status : List.of(401, 403, 302)) {
            Diagnostic hidden = diagnose(status, body, config("local", ""));
            check(hidden.info == null && !hidden.error.contains("PRIVATE_SERVER_DETAIL"), "auth or redirect body exposed");
        }
        Diagnostic generatedError = diagnose(200, completion("Error: PRIVATE_SERVER_DETAIL"), config("local", ""));
        check(generatedError.info == null && !generatedError.error.contains("PRIVATE_SERVER_DETAIL"), "200 model content became diagnostic detail");
        nextReply.set(new Reply(400, body));
        AtomicReference<String> legacyError = new AtomicReference<>();
        client.testConnection(config("local", ""), new AiSummaryClient.DiagnosticCallback() {
            public void onSuccess(AiSummaryClient.DiagnosticResult value) { throw new AssertionError("unexpected success"); }
            public void onError(String value) { legacyError.set(value); }
        });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (legacyError.get() == null && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
        client.cancel();
        check(legacyError.get() != null && !legacyError.get().contains("PRIVATE_SERVER_DETAIL"), "legacy callback no longer works safely");
    }

    private static void cancelDetailedDiagnostic() throws Exception {
        nextReply.set(new Reply(400, "{\"message\":\"STALE_ERROR_REASON\"}"));
        AiSummaryClient client = new AiSummaryClient(); Diagnostic result = new Diagnostic();
        client.testConnection(config("local", ""), result);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (AndroidUtilities.pendingImmediate() == 0 && System.nanoTime() < end) Thread.sleep(5);
        check(AndroidUtilities.pendingImmediate() > 0, "detailed error did not queue");
        client.cancel(); AndroidUtilities.drain();
        check(result.calls == 0 && result.info == null, "cancelled diagnostic detail delivered");
    }

    private static void mnnLocalApiContract() throws Exception {
        // Mirrors Naza3/MNN feature/mnn-chat-local-api LocalApiRoutes.parseLocalPrompt
        // with expanded 256 KiB / 65536-character limits; this is a protocol fixture,
        // not a claim of a real phone/model run.
        requestHistory.clear();
        AtomicInteger accepted = new AtomicInteger();
        replyGenerator.set(body -> {
            String failure = mnnLocalRequestFailure(body);
            if (failure != null) return new Reply(400, new JSONObject().put("error", new JSONObject().put("message", "Invalid request: " + failure)).toString());
            accepted.incrementAndGet();
            boolean diagnostic = "Reply OK.".equals(body.getJSONArray("messages").getJSONObject(1).getString("content"));
            String answer = diagnostic ? "OK" : "【回答】明天十点 [m1]";
            return body.getBoolean("stream") ? sse(delta(answer) + finish("stop") + "data: [DONE]\n\n")
                    : new Reply(200, completion(answer));
        });
        AiSummaryClient client = new AiSummaryClient();
        try {
            AiSummarySettings.Config plain = new AiSummarySettings.Config(base, "mnn-local", "", 512, false);
            AiSummarySettings.Config streaming = new AiSummarySettings.Config(base, "mnn-local", "", 512, true);
            Diagnostic diagnostic = new Diagnostic();
            client.testConnection(plain, diagnostic); await(diagnostic);
            check(diagnostic.result != null, "MNN contract rejected diagnostic: " + diagnostic.error);
            for (AiSummarySettings.Config config : List.of(plain, streaming)) {
                Result summary = new Result();
                client.summarize(config, MESSAGES, TEST_PROMPT, summary); await(summary);
                check(summary.summary != null, "MNN contract rejected summary: " + summary.error);
                Result question = new Result();
                client.ask(config, MESSAGES, PromptOptions.DEFAULT, "几点？", List.of(), question); await(question);
                check(question.summary != null, "MNN contract rejected question: " + question.error);
                if (config.stream) check(!summary.partials.isEmpty() && !question.partials.isEmpty(), "MNN stream contract bypassed SSE");
            }
            check(accepted.get() == 5 && requestHistory.size() == 5, "contract task retried or did not reach inference fixture");
            for (int i = 0; i < requestHistory.size(); i++) {
                JSONObject body = requestHistory.get(i);
                check("mnn-local".equals(body.getString("model")) && body.getJSONArray("messages").length() == 2,
                        "configured model or prompt structure was changed");
                check(body.getInt("max_tokens") == 512, "configured output limit changed between paths");
                check("system".equals(body.getJSONArray("messages").getJSONObject(0).getString("role"))
                        && "user".equals(body.getJSONArray("messages").getJSONObject(1).getString("role")), "message roles changed");
            }
            JSONObject previousPayload = new JSONObject(requestHistory.get(0).toString()).put("temperature", 0.2);
            check("Unsupported generation option".equals(mnnLocalRequestFailure(previousPayload)), "fixture does not reproduce the old HTTP 400 cause");
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static String mnnLocalRequestFailure(JSONObject body) {
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024) return "Request body exceeds 256 KiB";
        Object model = body.opt("model");
        if (model != null && model != JSONObject.NULL && !"mnn-local".equals(model) && !"loaded-fixture-model".equals(model)) return "Requested model is not loaded";
        for (String unsupported : List.of("tools", "tool_choice", "functions", "function_call", "response_format", "temperature", "top_p", "stop", "logprobs", "frequency_penalty", "presence_penalty", "n")) {
            if (body.has(unsupported) && !body.isNull(unsupported)) return "Unsupported generation option";
        }
        int tokens = body.optInt("max_tokens", 512);
        if (tokens < 1 || tokens > 2048) return "max_tokens must be 1..2048";
        org.json.JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0 || messages.length() > 64) return "messages must contain 1..64 entries";
        int characters = 0;
        boolean hasUser = false;
        StringBuilder allText = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !List.of("system", "user", "assistant").contains(message.optString("role"))) return "Unsupported message role";
            hasUser |= "user".equals(message.optString("role"));
            Object content = message.opt("content");
            if (!(content instanceof String)) return "Message content must be a string";
            characters += ((String) content).length();
            allText.append((String) content);
        }
        if (!hasUser) return "A user message is required";
        if (java.util.regex.Pattern.compile("<\\s*/?\\s*(img|image|audio|video)\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(allText).find()) return "Media markup is not supported";
        if (characters > 65536) return "Message text exceeds 65536 characters";
        if (body.has("stream") && !(body.opt("stream") instanceof Boolean)) return "stream must be a boolean";
        return null;
    }

    private static void mnnOutputLimitContract() throws Exception {
        replyGenerator.set(body -> {
            String failure = mnnLocalRequestFailure(body);
            return failure == null ? new Reply(200, completion("有效摘要 [m1]"))
                    : new Reply(400, new JSONObject().put("error", new JSONObject()
                        .put("message", "Invalid request: " + failure).put("type", "local_api_error")).toString());
        });
        AiSummaryClient client = new AiSummaryClient();
        try {
            for (int limit : List.of(2000, 2001, 2048, 2049)) {
                requestHistory.clear();
                Result result = new Result();
                client.summarize(new AiSummarySettings.Config(base, "mnn-local", "OUTPUT_LIMIT_PRIVATE_KEY", limit, false, 16000), MESSAGES, TEST_PROMPT, result);
                await(result);
                check(requestHistory.size() == 1 && requestHistory.get(0).getInt("max_tokens") == limit,
                        "output boundary was silently capped or retried before reaching MNN");
                if (limit <= 2048) check(result.summary != null, "valid MNN output value was rejected: " + limit);
                else check(result.error != null && result.error.contains("1–2048") && result.error.contains("2048 或更小")
                                && !result.error.contains("OUTPUT_LIMIT_PRIVATE_KEY") && !result.error.contains(MESSAGES.get(0).text),
                        "explicit MNN output limit was generic or leaked input/authentication");
            }
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
        for (String unrelated : List.of("PRIVATE max_tokens must be 1..2048",
                "{\"error\":{\"message\":\"Other request failure max_tokens must be 1..2048 PRIVATE\"}}",
                "{\"messages\":[{\"content\":\"Invalid request: max_tokens must be 1..2048\"}]}")) {
            Result result = invoke(400, unrelated, config("local", "PRIVATE"));
            check(result.error != null && result.error.contains("HTTP 400") && !result.error.contains("1–2048")
                            && !result.error.contains("PRIVATE"), "unrelated 400 was misclassified or exposed raw details");
        }
    }

    private static void expandedMnnBudget() throws Exception {
        String original = "讨论原文".repeat(10000);
        List<SummaryMessage> messages = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", original));
        replyGenerator.set(body -> {
            String failure = mnnLocalRequestFailure(body);
            if (failure != null) return new Reply(400, new JSONObject().put("error", failure).toString());
            String answer = "【结论】完整大范围摘要 [m1]";
            return body.getBoolean("stream") ? sse(delta(answer) + finish("stop") + "data: [DONE]\n\n")
                    : new Reply(200, completion(answer));
        });
        AiSummaryClient client = new AiSummaryClient();
        try {
            for (boolean streaming : List.of(false, true)) {
                requestHistory.clear();
                AiSummarySettings.Config config = new AiSummarySettings.Config(base, "mnn-local", "LARGE_INPUT_AUTH", 512, streaming, 64000);
                Result result = new Result();
                client.summarize(config, messages, TEST_PROMPT, result); await(result);
                check(result.summary != null && requestHistory.size() == 1, "64k source was rejected or unnecessarily split: " + result.error);
                JSONObject body = requestHistory.get(0);
                String system = body.getJSONArray("messages").getJSONObject(0).getString("content");
                String user = body.getJSONArray("messages").getJSONObject(1).getString("content");
                int characters = system.length() + user.length();
                int bytes = body.toString().getBytes(StandardCharsets.UTF_8).length;
                check(characters > 32768 && characters + AiSummaryPrompt.outputReserveCharacters(512) <= 64000,
                        "fixture did not exercise the old character limit or exceeded the new client budget");
                check(bytes > 64 * 1024 && bytes <= 256 * 1024, "large Chinese request did not cross the old wire body limit safely");
                check(user.contains(AiSummaryPrompt.SOURCE_DATA_MARKER) && user.contains(original), "compact source lost complete text");
                check("Bearer LARGE_INPUT_AUTH".equals(lastAuthorization.get()) && body.getInt("max_tokens") == 512,
                        "expanded input budget changed authentication or output limit");
                assertRequestInputs(result, requestHistory);
                check(mnnLocalRequestFailure(new JSONObject(body.toString()).put("max_tokens", 2049)) != null,
                        "MNN output limit unexpectedly increased with the input budget");
            }
            requestHistory.clear();
            Result invalid = new Result();
            client.summarize(new AiSummarySettings.Config(base, "mnn-local", "", 512, false, 64001), messages, TEST_PROMPT, invalid);
            await(invalid);
            check(invalid.error != null && requestHistory.isEmpty(), "64001 budget reached HTTP instead of failing validation");
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void diagnosticConfiguredOutputLimit() throws Exception {
        requestHistory.clear();
        for (int limit : List.of(64, 512, 1024, 2048)) {
            AiSummarySettings.Config settings = new AiSummarySettings.Config(base, "mnn-local", "", limit, true);
            Diagnostic result = diagnose(200, completion("OK"), settings);
            check(result.result != null && result.error == null, "configured probe failed");
            JSONObject body = lastBody.get();
            check(body.getInt("max_tokens") == limit && !body.getBoolean("stream"), "probe ignored its configured output limit or streamed");
            check(body.getJSONArray("messages").length() == 2
                    && "Reply OK.".equals(body.getJSONArray("messages").getJSONObject(1).getString("content")), "probe stopped using fixed text");
            check(!body.toString().contains(MESSAGES.get(0).text) && mnnLocalRequestFailure(body) == null,
                    "probe included chat or an unsupported MNN option");
        }
        check(requestHistory.size() == 4, "configured probe silently retried");
    }

    private static void diagnosticOutputLimitFailure() throws Exception {
        ArrayList<JSONObject> choices = new ArrayList<>();
        choices.add(new JSONObject().put("finish_reason", "length"));
        for (Object content : List.of(JSONObject.NULL, "", "OK", "<think>PRIVATE_DIAGNOSTIC_THOUGHT",
                "<think>PRIVATE_DIAGNOSTIC_THOUGHT</think>OK")) {
            choices.add(new JSONObject().put("finish_reason", "length")
                    .put("message", new JSONObject().put("content", content)));
        }
        requestHistory.clear();
        for (int limit : List.of(64, 512, 1024, 2048)) {
            for (JSONObject choice : choices) {
                String response = new JSONObject().put("choices", new org.json.JSONArray().put(choice)).toString();
                Diagnostic result = diagnose(200, response, new AiSummarySettings.Config(base, "mnn-local", "", limit));
                check(result.result == null && result.error != null && result.info == null, "truncated probe succeeded or exposed response details");
                check(result.error.contains("模型 API 已响应") && result.error.contains("服务端报告达到输出上限（本次设置 " + limit + " tokens）")
                        && result.error.contains("未完成文本生成验证"), "probe limit error did not identify configured limit and incomplete validation");
                check(!result.error.contains("PRIVATE_DIAGNOSTIC_THOUGHT") && !result.error.contains("OK"), "truncated content leaked into probe error");
                check(!result.error.contains("请提高") && result.error.contains("检查模型 API 配置"),
                        "probe recommends increasing the cap without diagnosing generation");
                check(result.error.contains("检测到思考内容") == choice.toString().contains("<think>"),
                        "probe thinking advice must require observed reasoning");
                check(lastBody.get().getInt("max_tokens") == limit, "reported output limit differs from the transmitted limit");
            }
        }
        check(requestHistory.size() == 4 * choices.size(), "truncated probe retried automatically");
    }

    private static void chatOutputLimitFailure() throws Exception {
        String response = new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject()
                .put("finish_reason", "length").put("message", new JSONObject().put("content", "PRIVATE_TRUNCATED_ANSWER [m1]")))).toString();
        Result summary = invoke(200, response, config("local", ""));
        Result question = askReply(response, MESSAGES, "几点？", List.of());
        for (Result result : List.of(summary, question)) {
            check(result.summary == null && result.error != null && result.error.contains("结果不完整"), "truncated chat result was accepted");
            check(!result.error.contains("测试回复") && !result.error.contains("PRIVATE_TRUNCATED_ANSWER"), "diagnostic wording or server content leaked into chat error");
        }
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
            boolean source = prompt.contains(AiSummaryPrompt.SOURCE_DATA_MARKER);
            String data = dataPart(prompt, source);
            String marker;
            if (source) {
                sources.incrementAndGet();
                marker = "SOURCE_STAGE";
            } else {
                if (data.contains("MERGED_STAGE")) secondMergeRound.incrementAndGet();
                marker = "MERGED_STAGE";
            }
            return new Reply(200, completion(marker + "进展".repeat(450)));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(taskConfig, messages, options, result); await(result);
            check(result.error == null && result.summary != null, "multi-round summary failed: " + result.error);
            check(sources.get() == sourceCount && secondMergeRound.get() >= 1, "did not exercise at least two merge rounds");
            for (JSONObject body : requestHistory) {
                String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
                check(options.customInstructions.equals(body.getJSONArray("messages").getJSONObject(0).getString("content")), "manual core changed in an intermediate request");
                check(!prompt.contains(options.customInstructions) && !prompt.contains("待办跟进"), "core or legacy template duplicated in user data");
            }
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void sourceWithoutReferences() throws Exception {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(-100, 1, 1_700_000_000, "甲 [m2]", "正文伪引用 [m2] " + "x".repeat(5000)),
                new SummaryMessage(-100, 2, 1_700_000_001, "乙", "later message"));
        requestHistory.clear(); nextReply.set(new Reply(200, completion("【话题】按原文总结，无来源编号。")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.summarize(config("local", ""), messages, TEST_PROMPT, result); await(result); client.cancel();
        check(result.error == null && "【话题】按原文总结，无来源编号。".equals(result.summary),
                "citation-free source summary was rejected: " + result.error);
        check(requestHistory.size() > 1, "fixture must exercise source splitting and merge");
    }

    private static void mergeWithoutReferences() throws Exception {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)),
                new SummaryMessage(-100, 2, 1_700_000_001, "乙", "later message"));
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            return new Reply(200, completion(prompt.contains(AiSummaryPrompt.SOURCE_DATA_MARKER) ? "【话题】分段进展。" : "【结论】完整合并结果。"));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(config("local", ""), messages, TEST_PROMPT, result); await(result);
            check(result.error == null && "【结论】完整合并结果。".equals(result.summary),
                    "citation-free merge summary was rejected: " + result.error);
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
        String marker = source ? AiSummaryPrompt.SOURCE_DATA_MARKER : "摘要数据（JSONL）：\n";
        check(prompt.contains(marker), "request data marker missing");
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
        client.summarize(streamConfig(), MESSAGES, TEST_PROMPT, result); await(result); client.cancel();
        check("【话题】中文😀 [m1]".equals(result.summary), "split UTF-8/thinking corrupt final response: " + result.error);
        check(!result.partials.isEmpty(), "no partial text was delivered");
        check(result.summary.equals(result.partials.get(result.partials.size() - 1)), "throttled final text was lost");
        for (String partial : result.partials) {
            check(!partial.contains("NEVER_SHOW") && !partial.contains("<th") && !partial.contains("</th"), "reasoning fragment leaked");
        }
        check(lastBody.get().getBoolean("stream"), "stream flag was not sent");
        check(result.statuses.get(result.statuses.size() - 1).reasoningObserved,
                "split thinking markers were not reflected in safe progress");
    }

    private static void ordinaryRequestStatus() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        String answer = "【话题】状态应只携带计数 [m1]";
        nextReply.set(new Reply(200, completion(answer), true));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(config("local", "STATUS_CREDENTIAL_SECRET"), MESSAGES, TEST_PROMPT, result);
            check(requestStarted.await(5, TimeUnit.SECONDS), "ordinary request did not arrive");
            AndroidUtilities.drain();
            check(result.statuses.size() == 1 && result.statuses.get(0).phase == AiSummaryClient.RequestPhase.SENDING,
                    "ordinary mode claimed a response before the server replied");
            releaseRequest.countDown(); await(result);
            check(answer.equals(result.summary), "ordinary status broke the answer");
            check(result.statuses.size() == 3, "ordinary request status sequence is incomplete");
            check(result.statuses.get(1).phase == AiSummaryClient.RequestPhase.RESPONSE
                    && result.statuses.get(2).phase == AiSummaryClient.RequestPhase.CONTENT, "ordinary status order changed");
            JSONObject body = lastBody.get();
            assertRequestInputs(result, List.of(body));
            check(!result.inputs.get(0).systemText.contains("STATUS_CREDENTIAL_SECRET")
                    && !result.inputs.get(0).userText.contains("STATUS_CREDENTIAL_SECRET"), "input callback included credentials");
            check(result.partials.isEmpty(), "ordinary mode unexpectedly produced a draft");
            int input = body.getJSONArray("messages").getJSONObject(0).getString("content").length()
                    + body.getJSONArray("messages").getJSONObject(1).getString("content").length();
            long elapsed = -1;
            for (AiSummaryClient.RequestStatus status : result.statuses) {
                check(!status.streaming && !status.reasoningObserved && status.inputCharacters == input
                        && status.maxOutputTokens == 512 && status.elapsedMs >= elapsed, "inaccurate ordinary request statistics");
                elapsed = status.elapsedMs;
            }
            check(result.statuses.get(2).receivedCharacters == answer.length(), "JSON framing counted as model content");
            check(result.statuses.get(0).responseElapsedMs == -1 && result.statuses.get(0).firstContentElapsedMs == -1,
                    "timing cannot claim response/content before the server replied");
            check(result.statuses.get(1).responseElapsedMs >= 0 && result.statuses.get(1).firstContentElapsedMs == -1,
                    "response timing must not invent generated content timing");
            AiSummaryClient.RequestStatus completedStatus = result.statuses.get(2);
            check(completedStatus.firstContentElapsedMs >= completedStatus.responseElapsedMs
                    && completedStatus.elapsedMs >= completedStatus.firstContentElapsedMs,
                    "ordinary complete-body timing must follow HTTP response timing");
            for (java.lang.reflect.Field field : AiSummaryClient.RequestStatus.class.getDeclaredFields()) {
                check(field.getType().isPrimitive() || field.getType() == AiSummaryClient.RequestPhase.class,
                        "request status can retain raw payload or credentials");
            }
        } finally {
            client.cancel(); releaseRequest.countDown(); requestStarted = null; releaseRequest = null;
        }
    }

    private static void streamRequestStatus() throws Exception {
        CountDownLatch allowReasoning = new CountDownLatch(1), allowAnswer = new CountDownLatch(1);
        String path = "/status-stream/v1/chat/completions";
        String hidden = "STATUS_THOUGHT_SECRET", answer = "【话题】最终正文 [m1]";
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write((": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                allowReasoning.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(("data: " + new JSONObject().put("choices", new org.json.JSONArray().put(
                        new JSONObject().put("delta", new JSONObject().put("reasoning_content", hidden)))) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                allowAnswer.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write((delta(answer) + finish("stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { }
            finally { exchange.close(); }
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(new AiSummarySettings.Config(base + "/status-stream/v1", "local", "STATUS_AUTH_SECRET", 512, true), MESSAGES, TEST_PROMPT, result);
            awaitStatus(() -> result.statuses.stream().anyMatch(status -> status.phase == AiSummaryClient.RequestPhase.RESPONSE));
            check(result.statuses.size() == 2 && result.statuses.get(1).streaming
                    && result.statuses.get(1).receivedCharacters == 0, "empty role or heartbeat counted as generated text");
            check(result.partials.isEmpty(), "empty SSE event produced answer text");
            allowReasoning.countDown();
            awaitStatus(() -> result.statuses.stream().anyMatch(status -> status.reasoningObserved));
            AiSummaryClient.RequestStatus reasoning = result.statuses.get(result.statuses.size() - 1);
            check(reasoning.phase == AiSummaryClient.RequestPhase.CONTENT && reasoning.receivedCharacters == hidden.length()
                    && result.partials.isEmpty() && result.calls == 0, "reasoning was invisible to status or exposed as answer");
            check(reasoning.firstContentElapsedMs >= reasoning.responseElapsedMs && reasoning.responseElapsedMs >= 0,
                    "hidden reasoning contributes only to received-content timing, not a visible answer");
            allowAnswer.countDown(); await(result);
            check(answer.equals(result.summary), "reasoning field contaminated final answer");
            AiSummaryClient.RequestStatus last = result.statuses.get(result.statuses.size() - 1);
            check(last.receivedCharacters == hidden.length() + answer.length() && last.reasoningObserved,
                    "final accumulated content statistics were not flushed");
            check(last.firstContentElapsedMs == reasoning.firstContentElapsedMs,
                    "first received-content timing must remain fixed when answer text later arrives");
            for (String partial : result.partials) check(!partial.contains(hidden), "reasoning field leaked in a partial");
        } finally {
            client.cancel(); allowReasoning.countDown(); allowAnswer.countDown(); server.removeContext(path);
        }
    }

    private static void awaitStatus(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
        check(condition.getAsBoolean(), "request status did not arrive before timeout");
    }

    private static void observedOutputLimits() throws Exception {
        for (boolean stream : new boolean[]{false, true}) for (int reasoning : new int[]{0, 1, 2}) {
            JSONObject payload = new JSONObject().put("content", reasoning == 1 ? "<think>LIMIT_SECRET" : "LIMIT_ANSWER_SECRET [m1]");
            if (reasoning == 2) payload.put("reasoning", "LIMIT_REASONING_SECRET");
            JSONObject choice = new JSONObject().put("finish_reason", "length").put(stream ? "delta" : "message", payload);
            String response = new JSONObject().put("choices", new org.json.JSONArray().put(choice)).toString();
            nextReply.set(stream ? sse("data: " + response + "\n\ndata: [DONE]\n\n") : new Reply(200, response));
            AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
            try {
                client.summarize(new AiSummarySettings.Config(base, "local", "LIMIT_AUTH_SECRET", 2000, stream, 32000), MESSAGES, TEST_PROMPT, result);
                await(result);
                check(result.summary == null && result.error.contains("2000 tokens") && result.error.contains("结果不完整"),
                        "length error lost actual configured cap or accepted incomplete answer");
                check(result.error.contains("检测到思考内容") == (reasoning != 0), "reasoning guidance was guessed or lost");
                check(!result.error.contains("SECRET") && result.partials.isEmpty(), "truncated answer/reasoning/credential leaked");
                check(result.statuses.get(result.statuses.size() - 1).reasoningObserved == (reasoning != 0),
                        "truncated response lost its safe reasoning observation");
            } finally { client.cancel(); }
        }
    }

    private static void streamEveryStage() throws Exception {
        List<SummaryMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            messages.add(new SummaryMessage(-100, i, 1_700_000_000 + i, "甲", "项目进展".repeat(800)));
        }
        List<String> answers = new CopyOnWriteArrayList<>();
        AtomicInteger sources = new AtomicInteger(), secondMergeRound = new AtomicInteger();
        requestHistory.clear();
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            boolean source = prompt.contains(AiSummaryPrompt.SOURCE_DATA_MARKER);
            String data = dataPart(prompt, source);
            if (source) sources.incrementAndGet();
            else if (data.contains("MERGED_STAGE")) secondMergeRound.incrementAndGet();
            String start = "【话题】" + (source ? "SOURCE_STAGE_" : "MERGED_STAGE_") + (answers.size() + 1);
            String tail = "进展".repeat(450);
            answers.add(start + tail);
            return new Reply(200, delta("<think>STAGE_THOUGHT_SECRET</think>" + start) + delta(tail)
                    + finish("stop") + "data: [DONE]\n\n", false, "text/event-stream", 0);
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(streamConfig(), messages, TEST_PROMPT, result); await(result);
            check(result.summary != null, "final streaming merge failed: " + result.error);
            check(sources.get() >= 8 && secondMergeRound.get() >= 1, "fixture must include multiple source and merge rounds");
            check(result.calls == 1 && answers.get(answers.size() - 1).equals(result.summary), "draft became a final result");
            assertRequestInputs(result, requestHistory);
            for (int i = 0; i < requestHistory.size(); i++) {
                check(requestHistory.get(i).getBoolean("stream"), "intermediate request lost streaming observability");
                String user = result.inputs.get(i).userText;
                if (i < sources.get()) check(!user.contains("SOURCE_STAGE_"), "previous draft was added to source input");
                else check(!user.contains("项目进展"), "merge input unexpectedly repeated source history");
            }
            int requestIndex = -1;
            String lastPartial = null;
            for (String event : result.events) {
                if (event.equals("SENDING")) {
                    if (requestIndex >= 0) check(answers.get(requestIndex).equals(lastPartial), "request boundary lost a draft tail");
                    requestIndex++;
                    lastPartial = null;
                } else if (event.startsWith("PARTIAL:")) {
                    lastPartial = event.substring("PARTIAL:".length());
                    check(requestIndex >= 0 && answers.get(requestIndex).startsWith(lastPartial),
                            "partial crossed a request boundary or exposed thinking");
                }
            }
            check(requestIndex == answers.size() - 1 && answers.get(requestIndex).equals(lastPartial),
                    "final request's throttled tail was not displayed");
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

    private static void assertRequestInputs(Result result, List<JSONObject> requests) {
        check(result.inputs.size() == requests.size(), "request input callback missing or duplicated");
        int sending = 0;
        for (int i = 0; i < result.events.size(); i++) {
            if (result.events.get(i).equals("SENDING")) {
                sending++;
                check(i + 1 < result.events.size() && result.events.get(i + 1).equals("INPUT"),
                        "exact input did not immediately follow the new request boundary");
            }
        }
        check(sending == requests.size(), "request must have exactly one SENDING before its input and drafts");
        List<AiSummaryClient.RequestStatus> sent = new ArrayList<>();
        for (AiSummaryClient.RequestStatus status : result.statuses) {
            if (status.phase == AiSummaryClient.RequestPhase.SENDING) sent.add(status);
        }
        for (int i = 0; i < requests.size(); i++) {
            org.json.JSONArray messages = requests.get(i).getJSONArray("messages");
            AiSummaryClient.RequestInput input = result.inputs.get(i);
            check(messages.length() == 2 && "system".equals(messages.getJSONObject(0).getString("role"))
                    && "user".equals(messages.getJSONObject(1).getString("role")), "unexpected extra request history");
            check(input.systemText.equals(messages.getJSONObject(0).getString("content"))
                    && input.userText.equals(messages.getJSONObject(1).getString("content")),
                    "displayed input differs from the exact transmitted messages");
            check(input.inputCharacters == input.systemText.length() + input.userText.length()
                    && input.inputCharacters == sent.get(i).inputCharacters, "displayed input count differs from transport");
        }
    }

    private static void streamFailureTails() throws Exception {
        String safe = "【话题】公开正文和末尾 [m1]";
        String secret = "TAIL_THOUGHT_SECRET";
        String sameEvent = "data: " + new JSONObject().put("choices", new org.json.JSONArray().put(
                new JSONObject().put("delta", new JSONObject().put("content", "和末尾 [m1]"))
                        .put("finish_reason", "length"))) + "\n\n";
        String[] streams = {
                delta("<think>" + secret + "</think>【话题】公开正文") + sameEvent,
                delta("【话题】公开正文") + delta("和末尾 [m1]") + finish("length"),
                delta("【话题】公开正文") + delta("和末尾 [m1]"), // EOF before a completion marker.
                delta("<think mode=analysis>" + secret) + finish("length"),
                delta("unmarked " + secret + " [m1]") + finish("length")
        };
        for (int i = 0; i < streams.length; i++) {
            requestHistory.clear();
            nextReply.set(new Reply(200, streams[i], false, "text/event-stream", 0));
            AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
            try {
                client.summarize(streamConfig(), MESSAGES, TEST_PROMPT, result); await(result);
                check(result.summary == null && result.error != null && result.calls == 1 && requestHistory.size() == 1,
                        "incomplete stream succeeded or retried automatically");
                check(result.events.get(result.events.size() - 1).equals("ERROR"), "draft arrived after the terminal error");
                if (i < 3) {
                    check(!result.partials.isEmpty() && safe.equals(result.partials.get(result.partials.size() - 1)),
                            "safe final delta was swallowed by length handling or throttling");
                } else check(result.partials.isEmpty(), "reasoning or unmarked preamble became a visible draft");
                if (i != 2) check(result.error.contains("512 tokens"), "length failure lost the configured cap");
                for (String partial : result.partials) check(!partial.contains(secret), "thinking leaked on stream failure");
                check(!result.error.contains(secret), "thinking leaked into the error");
            } finally { client.cancel(); }
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
                delta("【话题】已完成 [m1]") + finish("stop")
                        + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"DO_NOT_ECHO_SECRET\"}}]}\n\n"
                        + "data: [DONE]\n\n",
                ":" + "x".repeat(1_048_576) + "\n\n"
        };
        for (String stream : streams) {
            // Large-limit case uses normal chunks to avoid a million individual socket writes.
            nextReply.set(new Reply(200, stream, false, "text/event-stream", 0));
            AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
            client.summarize(streamConfig(), MESSAGES, TEST_PROMPT, result); await(result); client.cancel();
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
            client.summarize(new AiSummarySettings.Config(base + "/stream-cancel/v1", "local", "", 512, true), MESSAGES, TEST_PROMPT, stale);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (stale.partials.isEmpty() && System.nanoTime() < end) { AndroidUtilities.drain(); Thread.sleep(5); }
            check(!stale.partials.isEmpty(), "stream did not deliver an initial partial");
            long cancelStarted = System.nanoTime();
            client.cancel();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStarted) < 1000,
                    "cancel blocked the caller while the server kept streaming open");
            releaseStream.countDown();
            int partialCount = stale.partials.size(), progressCount = stale.progress.size(), statusCount = stale.statuses.size();
            int inputCount = stale.inputs.size();
            nextReply.set(new Reply(200, completion("新的普通请求 [m1]")));
            Result fresh = afterCancellation(client);
            check(stale.calls == 0 && stale.partials.size() == partialCount && stale.progress.size() == progressCount
                    && stale.statuses.size() == statusCount && stale.inputs.size() == inputCount,
                    "cancelled stream delivered a later callback");
            check(fresh.summary != null, "client did not recover after stream cancellation");
        } finally {
            client.cancel(); releaseStream.countDown(); server.removeContext(path);
        }
    }

    private static void cancelQueuedRequestInput() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        nextReply.set(new Reply(200, completion("取消的请求 [m1]"), true));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.summarize(config("local", ""), MESSAGES, TEST_PROMPT, result);
            check(requestStarted.await(5, TimeUnit.SECONDS), "request never reached the server");
            // Both SENDING and the sensitive input are queued, but the UI has not consumed them.
            client.cancel();
            releaseRequest.countDown(); AndroidUtilities.drain();
            nextReply.set(new Reply(200, completion("取消之后的新请求 [m1]")));
            check(afterCancellation(client).summary != null, "cancelled worker did not release its endpoint");
            check(result.inputs.isEmpty() && result.statuses.isEmpty() && result.partials.isEmpty() && result.calls == 0,
                    "cancelled request exposed a queued input or result");
        } finally {
            client.cancel(); releaseRequest.countDown(); requestStarted = null; releaseRequest = null;
        }
    }

    private static Result afterCancellation(AiSummaryClient client) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Result result = new Result();
            client.summarize(config("local", ""), MESSAGES, TEST_PROMPT, result); await(result);
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
                check(direction.customInstructions.equals(body.getJSONArray("messages").getJSONObject(0).getString("content")), "budget path lost manual core");
                check(!prompt.contains(direction.customInstructions), "manual core duplicated into data prompt");
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
            first.summarize(config("model-one", ""), MESSAGES, TEST_PROMPT, blocked);
            check(requestStarted.await(5, TimeUnit.SECONDS), "first endpoint task did not start");
            Result rejected = new Result(); second.summarize(config("model-two", ""), MESSAGES, TEST_PROMPT, rejected); await(rejected);
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

    private static void questionSnapshot() throws Exception {
        List<SummaryMessage> messages = new ArrayList<>(MESSAGES);
        List<SummaryQuestionPrompt.Turn> history = new ArrayList<>(List.of(
                new SummaryQuestionPrompt.Turn("上一轮问题KEEP_OLD", "上一轮回答 [m1]")));
        nextReply.set(new Reply(200, completion("【回答】明天十点 [m1]")));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.ask(config("local", ""), messages, PromptOptions.DEFAULT, "会议定于几点？", history, result);
        messages.clear(); messages.add(new SummaryMessage(-200, 99, 1_700_000_000, "别的群", "DO_NOT_SEND_NEW_SOURCE"));
        history.clear(); history.add(new SummaryQuestionPrompt.Turn("DO_NOT_SEND_NEW_HISTORY", "变更历史 [m1]"));
        await(result); client.cancel();
        JSONObject body = lastBody.get();
        check(result.summary != null, "question failed: " + result.error);
        check(SummaryQuestionPrompt.SYSTEM_PROMPT.equals(body.getJSONArray("messages").getJSONObject(0).getString("content")), "summary system rules used for question");
        String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
        check(prompt.contains("会议定于几点？") && prompt.contains("KEEP_OLD") && prompt.contains(MESSAGES.get(0).text), "question snapshot lost context");
        check(!prompt.contains("DO_NOT_SEND_NEW"), "caller changed pending question inputs");
    }

    private static void questionAcrossMergeRounds() throws Exception {
        String question = "当前决定和依据是什么QUESTION_93？";
        PromptOptions direction = new PromptOptions(PromptOptions.DECISIONS, "QUESTION_DIRECTION_FIXED");
        List<SummaryQuestionPrompt.Turn> history = List.of(new SummaryQuestionPrompt.Turn("HISTORY_33", "历史回答 [m1]"));
        List<SummaryMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) messages.add(new SummaryMessage(-100, i, 1_700_000_000 + i, "甲", "项目决定".repeat(700)));
        requestHistory.clear(); AtomicInteger secondMergeRound = new AtomicInteger();
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            boolean source = prompt.contains("原始消息（JSONL 数据）：\n");
            String data = questionData(prompt, source);
            int ref;
            if (source) ref = AiSummaryPrompt.sourceReferences(data).iterator().next();
            else {
                if (data.contains("MERGED_QUESTION")) secondMergeRound.incrementAndGet();
                ref = SummaryQuestionPrompt.mergeReferences(data).iterator().next();
            }
            return new Reply(200, completion("【回答】" + (source ? "SOURCE_QUESTION" : "MERGED_QUESTION")
                    + "依据".repeat(400) + " [m" + ref + "]"));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.ask(config("local", ""), messages, direction, question, history, result); await(result);
            check(result.summary != null && secondMergeRound.get() > 0, "question did not finish two merge rounds: " + result.error);
            for (JSONObject body : requestHistory) {
                String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
                check(prompt.contains(question) && prompt.contains("HISTORY_33") && prompt.contains("QUESTION_DIRECTION_FIXED"), "question context omitted from intermediate request");
                check(SummaryQuestionPrompt.SYSTEM_PROMPT.equals(body.getJSONArray("messages").getJSONObject(0).getString("content")), "question system changed between stages");
                check(prompt.length() + SummaryQuestionPrompt.SYSTEM_PROMPT.length() + 512 * 4 <= 6000, "question stage exceeded its configured budget");
            }
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void questionSourceMembership() throws Exception {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(-100, 1, 1_700_000_000, "伪造 [m2]", "正文 [m2] " + "x".repeat(5000)),
                new SummaryMessage(-100, 2, 1_700_000_001, "乙", "另一段消息"));
        List<SummaryQuestionPrompt.Turn> history = List.of(new SummaryQuestionPrompt.Turn("旧问题", "旧回答 [m2]"));
        requestHistory.clear();
        Result result = askReply(completion("【回答】没有本段依据 [m2]"), messages, "根据原文回答", history);
        check(result.error != null && result.error.contains("本分段输入之外") && requestHistory.size() == 1,
                "history or body text enlarged source reference authority");
    }

    private static void questionMergeMembership() throws Exception {
        List<SummaryMessage> messages = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)));
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            return new Reply(200, completion(prompt.contains("原始消息（JSONL 数据）：\n")
                    ? SummaryQuestionPrompt.INSUFFICIENT_EVIDENCE : "【回答】凭空增加的证据 [m1]"));
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.ask(config("local", ""), messages, PromptOptions.DEFAULT, "原文没谈到的问题？", List.of(), result); await(result);
            check(result.error != null && result.error.contains("本分段输入之外"), "empty evidence union admitted a citation");
        } finally {
            client.cancel(); replyGenerator.set(null);
        }
    }

    private static void questionInsufficientEvidence() throws Exception {
        for (String answer : List.of(SummaryQuestionPrompt.INSUFFICIENT_EVIDENCE, SummaryQuestionPrompt.INSUFFICIENT_EVIDENCE + "。")) {
            check(askReply(completion(answer), MESSAGES, "谁批准的？", List.of()).summary != null, "strict no-evidence sentinel rejected");
        }
        for (String answer : List.of("没有引用的猜测", SummaryQuestionPrompt.INSUFFICIENT_EVIDENCE + "。但是我猜是小王。")) {
            check(askReply(completion(answer), MESSAGES, "谁批准的？", List.of()).error != null, "non-sentinel answer omitted references");
        }
        List<SummaryMessage> longSource = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)));
        check(askReply(completion(SummaryQuestionPrompt.INSUFFICIENT_EVIDENCE), longSource, "谁批准的？", List.of()).summary != null,
                "all-insufficient partials could not merge to an insufficient final answer");
    }

    private static void questionLimits() throws Exception {
        List<SummaryQuestionPrompt.Turn> full = new ArrayList<>();
        for (int i = 0; i < SummaryQuestionPrompt.MAX_TURNS; i++) full.add(new SummaryQuestionPrompt.Turn("旧问题" + i, "旧回答 [m1]"));
        requestHistory.clear();
        Result result = askReply(completion("不应请求 [m1]"), MESSAGES, "第六个问题", full);
        check(result.error != null && requestHistory.isEmpty() && full.size() == SummaryQuestionPrompt.MAX_TURNS,
                "turn limit silently dropped history or sent HTTP");
        check(askReply(completion("不应请求 [m1]"), MESSAGES, "x".repeat(501), List.of()).error != null && requestHistory.isEmpty(),
                "oversized question reached service");
    }

    private static void ordinaryThinkingFailures() throws Exception {
        for (String content : List.of("<think mode=analysis>INTERNAL_REASONING [m1]",
                "<THINK\nmode=analysis>INTERNAL_REASONING [m1]",
                "【回答】可见文字 [m1]<think mode=analysis",
                "【回答】可见文字 [m1]<thi", "【回答】可见文字 [m1]</thin",
                "<think>INTERNAL_REASONING [m1]<think>nested</think>")) {
            for (Result result : List.of(invoke(200, completion(content), config("local", "")),
                    askReply(completion(content), MESSAGES, "几点？", List.of()))) {
                check(result.summary == null && result.error != null && result.error.contains("思考"),
                        "unfinished thinking became a final answer");
                check(!result.error.contains("INTERNAL_REASONING") && result.partials.isEmpty(),
                        "reasoning leaked via an error or ordinary partial callback");
            }
        }
    }

    private static void questionStreaming() throws Exception {
        nextReply.set(sse(delta("<think>HIDDEN_QUESTION_THOUGHT</think>【回答】") + delta("明天十点 [m1]")
                + finish("stop") + "data: [DONE]\n\n"));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.ask(streamConfig(), MESSAGES, PromptOptions.DEFAULT, "几点？", List.of(), result); await(result); client.cancel();
        check("【回答】明天十点 [m1]".equals(result.summary) && !result.partials.isEmpty(), "question streaming failed");
        for (String partial : result.partials) check(!partial.contains("HIDDEN_QUESTION"), "question thinking leaked");
    }

    private static void questionIntermediateDrafts() throws Exception {
        List<SummaryMessage> messages = List.of(new SummaryMessage(-100, 1, 1_700_000_000, "甲", "x".repeat(5000)));
        requestHistory.clear();
        replyGenerator.set(body -> {
            String prompt = body.getJSONArray("messages").getJSONObject(1).getString("content");
            String answer = prompt.contains("原始消息（JSONL 数据）：\n")
                    ? "【回答】QUESTION_INTERMEDIATE [m1]" : "【回答】QUESTION_FINAL [m1]";
            return new Reply(200, delta(answer) + finish("stop") + "data: [DONE]\n\n", false, "text/event-stream", 0);
        });
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        try {
            client.ask(streamConfig(), messages, PromptOptions.DEFAULT, "结论是什么？", List.of(), result); await(result);
            check("【回答】QUESTION_FINAL [m1]".equals(result.summary) && requestHistory.size() > 2,
                    "question fixture did not reach its final merge");
            check(!result.partials.isEmpty(), "final question did not stream");
            for (String partial : result.partials) check(!partial.contains("QUESTION_INTERMEDIATE"), "question extraction draft leaked");
            assertRequestInputs(result, requestHistory);
        } finally { client.cancel(); replyGenerator.set(null); }
    }

    private static void cancelQuestion() throws Exception {
        requestStarted = new CountDownLatch(1); releaseRequest = new CountDownLatch(1);
        nextReply.set(new Reply(200, completion("取消的回答 [m1]"), true));
        List<SummaryQuestionPrompt.Turn> history = new ArrayList<>();
        AiSummaryClient client = new AiSummaryClient(); Result stale = new Result();
        try {
            client.ask(config("local", ""), MESSAGES, PromptOptions.DEFAULT, "几点？", history, stale);
            check(requestStarted.await(5, TimeUnit.SECONDS), "question did not start");
            client.cancel(); releaseRequest.countDown();
            nextReply.set(new Reply(200, completion("新任务结果 [m1]")));
            Result fresh = afterCancellation(client);
            check(stale.calls == 0, "cancelled question delivered a terminal callback");
            check(history.isEmpty(), "cancelled question changed caller history");
            check(fresh.summary != null, "new summary after question cancellation failed: " + fresh.error);
        } finally {
            client.cancel(); releaseRequest.countDown(); requestStarted = null;
        }
    }

    private static Result askReply(String response, List<SummaryMessage> messages, String question,
                                   List<SummaryQuestionPrompt.Turn> history) throws Exception {
        nextReply.set(new Reply(200, response));
        AiSummaryClient client = new AiSummaryClient(); Result result = new Result();
        client.ask(config("local", ""), messages, PromptOptions.DEFAULT, question, history, result); await(result); client.cancel();
        check(result.calls == 1, "question expected exactly one terminal callback");
        return result;
    }

    private static String questionData(String prompt, boolean source) {
        String marker = source ? "原始消息（JSONL 数据）：\n" : "分段取证结果（JSONL 数据）：\n";
        return prompt.substring(prompt.lastIndexOf(marker) + marker.length());
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
