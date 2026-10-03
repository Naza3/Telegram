/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cancellable OpenAI-compatible completions with optional streaming of the final answer. */
public final class AiSummaryClient {
    public interface Callback {
        void onSuccess(String summary);
        void onError(String error);
        default void onProgress(Progress progress) {
        }
        /** Unvalidated accumulated text for display only; source links belong to onSuccess. */
        default void onPartial(String text) {
        }
    }

    public enum Stage {
        SOURCE, MERGE, VALIDATING
    }

    public static final class Progress {
        public final Stage stage;
        public final int completed;
        public final int total;
        public final int mergeRound;
        public final long elapsedMs;

        Progress(Stage stage, int completed, int total, int mergeRound, long elapsedMs) {
            this.stage = stage;
            this.completed = completed;
            this.total = total;
            this.mergeRound = mergeRound;
            this.elapsedMs = elapsedMs;
        }
    }

    public interface DiagnosticCallback {
        void onSuccess(DiagnosticResult result);
        void onError(String error);
    }

    public static final class DiagnosticResult {
        public final long elapsedMs;
        /** The response's model field, if present; never inferred from the requested model. */
        public final String reportedModel;

        DiagnosticResult(long elapsedMs, String reportedModel) {
            this.elapsedMs = elapsedMs;
            this.reportedModel = reportedModel;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    // Closing an actively read HttpURLConnection can block on its stream lock; never do it on UI.
    private static final ExecutorService DISCONNECT_EXECUTOR = Executors.newCachedThreadPool(action -> {
        Thread thread = new Thread(action, "ai-summary-disconnect");
        thread.setDaemon(true);
        return thread;
    });
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 300_000;
    private static final int DIAGNOSTIC_TIMEOUT_MS = 60_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_ERROR_BYTES = 16_384;
    private static final int MAX_MODEL_REQUESTS = 128;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int diagnosticTimeoutMs;
    private int generation;
    private Future<?> task;
    private HttpURLConnection connection;

    public AiSummaryClient() {
        this(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIAGNOSTIC_TIMEOUT_MS);
    }

    // Allows deterministic timeout tests without waiting for a mobile model's full timeout.
    AiSummaryClient(int connectTimeoutMs, int readTimeoutMs, int diagnosticTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || diagnosticTimeoutMs <= 0) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.diagnosticTimeoutMs = diagnosticTimeoutMs;
    }

    /** Sends fixed test text only. Does not read messages or persist configuration. */
    public synchronized void testConnection(AiSummarySettings.Config config, DiagnosticCallback callback) {
        cancel();
        final int request = generation;
        task = EXECUTOR.submit(() -> {
            try {
                String error = AiSummarySettings.validate(config);
                if (error != null) {
                    throw new SummaryException(error);
                }
                long started = System.nanoTime();
                Completion result = complete(config, "This is a connection test. Reply only OK, without reasoning.",
                        "Reply OK.", 64, diagnosticTimeoutMs, request, false, null);
                DiagnosticResult diagnostic = new DiagnosticResult(
                        Math.max(0, (System.nanoTime() - started) / 1_000_000L), result.reportedModel);
                deliver(request, () -> callback.onSuccess(diagnostic));
            } catch (CancelledException ignored) {
            } catch (Exception error) {
                deliver(request, () -> callback.onError(describeError(error)));
            } finally {
                finishTask(request);
            }
        });
    }

    public synchronized void summarize(AiSummarySettings.Config config, List<SummaryMessage> messages, Callback callback) {
        summarize(config, messages, PromptOptions.DEFAULT, callback);
    }

    public synchronized void summarize(AiSummarySettings.Config config, List<SummaryMessage> messages,
                                       PromptOptions options, Callback callback) {
        cancel();
        final int request = generation;
        final ArrayList<SummaryMessage> snapshot = messages == null ? null : new ArrayList<>(messages);
        final PromptOptions direction = options; // Immutable and fixed for all source/merge requests.
        task = EXECUTOR.submit(() -> {
            long started = System.nanoTime();
            try {
                String error = AiSummarySettings.validate(config);
                if (error != null) {
                    throw new SummaryException(error);
                }
                List<String> chunks = AiSummaryPrompt.sourceChunks(snapshot, direction);
                int requestCount = chunks.size();
                List<String> summaries = new ArrayList<>();
                progress(request, callback, Stage.SOURCE, 0, chunks.size(), 0, started);
                for (int i = 0; i < chunks.size(); i++) {
                    checkActive(request);
                    String chunk = chunks.get(i);
                    String summary = complete(config,
                            AiSummaryPrompt.sourcePrompt(chunk, i + 1, chunks.size(), direction), request,
                            chunks.size() == 1, callback);
                    AiSummaryPrompt.validateReferences(summary, AiSummaryPrompt.sourceReferences(chunk));
                    summaries.add(summary);
                    progress(request, callback, Stage.SOURCE, i + 1, chunks.size(), 0, started);
                }
                int mergeRound = 0;
                while (summaries.size() > 1) {
                    checkActive(request);
                    List<String> mergeChunks = AiSummaryPrompt.mergeChunks(summaries, direction);
                    requestCount += mergeChunks.size();
                    if (requestCount > MAX_MODEL_REQUESTS) {
                        throw new SummaryException("分段摘要无法在本次请求上限内完成合并。请减少消息数量后重试。");
                    }
                    List<String> merged = new ArrayList<>();
                    mergeRound++;
                    progress(request, callback, Stage.MERGE, 0, mergeChunks.size(), mergeRound, started);
                    for (String chunk : mergeChunks) {
                        String summary = complete(config, AiSummaryPrompt.mergePrompt(chunk, direction), request,
                                mergeChunks.size() == 1, callback);
                        // The chunk contains only summaries validated against their own inputs.
                        AiSummaryPrompt.validateReferences(summary, AiSummaryPrompt.references(chunk));
                        merged.add(summary);
                        progress(request, callback, Stage.MERGE, merged.size(), mergeChunks.size(), mergeRound, started);
                    }
                    summaries = merged;
                }
                String result = summaries.get(0);
                progress(request, callback, Stage.VALIDATING, 0, 1, mergeRound, started);
                AiSummaryPrompt.validateReferences(result, snapshot.size());
                progress(request, callback, Stage.VALIDATING, 1, 1, mergeRound, started);
                deliver(request, () -> callback.onSuccess(result));
            } catch (CancelledException ignored) {
                // Cancellation is not an error and never delivers a stale callback.
            } catch (Exception error) {
                deliver(request, () -> callback.onError(describeError(error)));
            } finally {
                finishTask(request);
            }
        });
    }

    public synchronized void cancel() {
        generation++;
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (connection != null) {
            HttpURLConnection cancelled = connection;
            connection = null;
            DISCONNECT_EXECUTOR.execute(cancelled::disconnect);
        }
    }

    private synchronized void checkActive(int request) throws CancelledException {
        if (request != generation || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private synchronized void finishTask(int request) {
        if (request == generation) {
            task = null;
        }
    }

    private void progress(int request, Callback callback, Stage stage, int completed, int total,
                          int mergeRound, long started) {
        Progress progress = new Progress(stage, completed, total, mergeRound,
                Math.max(0, (System.nanoTime() - started) / 1_000_000L));
        deliver(request, () -> callback.onProgress(progress));
    }

    private void deliver(int request, Runnable callback) {
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (AiSummaryClient.this) {
                if (request == generation) {
                    callback.run();
                }
            }
        });
    }

    private String complete(AiSummarySettings.Config config, String prompt, int request, boolean finalRequest,
                            Callback callback)
            throws IOException, JSONException, SummaryException, CancelledException {
        return complete(config, AiSummaryPrompt.SYSTEM_PROMPT, prompt, config.maxOutputTokens,
                readTimeoutMs, request, config.stream && finalRequest, callback).text;
    }

    private Completion complete(AiSummarySettings.Config config, String systemPrompt, String prompt,
                                int maxOutputTokens, int timeoutMs, int request, boolean stream, Callback callback)
            throws IOException, JSONException, SummaryException, CancelledException {
        checkActive(request);
        JSONObject body = new JSONObject();
        if (!config.model.isEmpty()) {
            body.put("model", config.model);
        }
        body.put("stream", stream);
        body.put("temperature", 0.2);
        // Some MNN Chat releases accept but ignore this compatibility parameter.
        body.put("max_tokens", maxOutputTokens);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                .put(new JSONObject().put("role", "user").put("content", prompt)));
        byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection current = (HttpURLConnection) new URL(AiSummarySettings.completionUrl(config)).openConnection();
        synchronized (this) {
            checkActive(request);
            connection = current;
        }
        try {
            current.setRequestMethod("POST");
            current.setInstanceFollowRedirects(false);
            current.setConnectTimeout(connectTimeoutMs);
            current.setReadTimeout(timeoutMs);
            current.setUseCaches(false);
            current.setDoOutput(true);
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            current.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            if (!config.apiKey.isEmpty()) {
                current.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }
            current.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = current.getOutputStream()) {
                checkActive(request);
                output.write(encoded);
            }
            int status = current.getResponseCode();
            checkActive(request);
            if (status < 200 || status >= 300) {
                if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
                    throw new SummaryException(httpError(status));
                }
                throw new SummaryException(serverError(status, readErrorBody(current, request)));
            }
            String contentType = current.getContentType();
            if (stream && contentType != null && contentType.toLowerCase(Locale.US).startsWith("text/event-stream")) {
                AiSummarySse.Result result;
                final long[] lastPartial = {0};
                try {
                    result = AiSummarySse.read(current.getInputStream(), MAX_RESPONSE_BYTES, () -> {
                        try {
                            checkActive(request);
                        } catch (CancelledException cancelled) {
                            throw new InterruptedIOException("Cancelled");
                        }
                    }, text -> {
                        long now = System.nanoTime();
                        if (callback != null && now - lastPartial[0] >= 75_000_000L) {
                            lastPartial[0] = now;
                            deliver(request, () -> callback.onPartial(text));
                        }
                    });
                } catch (AiSummarySse.Failure failure) {
                    throw new SummaryException(failure.serverDetail == null ? failure.getMessage()
                            : serverError(status, failure.serverDetail));
                } catch (JSONException error) {
                    throw new SummaryException("流式响应格式不兼容。请关闭流式模式后重试。");
                }
                checkActive(request);
                if (result.text.regionMatches(true, 0, "Error:", 0, 6)) {
                    throw new SummaryException(serverError(status, result.text));
                }
                return new Completion(result.text, safeReportedModel(result.reportedModel, config.apiKey));
            }
            String response;
            try (InputStream input = current.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    checkActive(request);
                    if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                        throw new SummaryException("模型服务返回的数据过大，已停止处理。请减少消息数量后重试。");
                    }
                    buffer.write(bytes, 0, read);
                }
                response = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject responseObject = new JSONObject(response);
            if (responseObject.has("error") && !responseObject.isNull("error")) {
                throw new SummaryException(serverError(status, responseObject.get("error").toString()));
            }
            JSONArray choices = responseObject.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new SummaryException("模型服务没有返回 choices 结果。请确认接口兼容 /v1/chat/completions。");
            }
            JSONObject choice = choices.getJSONObject(0);
            String finish = choice.optString("finish_reason");
            if ("length".equals(finish)) {
                throw new SummaryException("模型输出达到长度上限，结果不完整。请提高最大输出 tokens、关闭长思考模式或减少消息数量后重试。");
            }
            if ("content_filter".equals(finish)) {
                throw new SummaryException("模型服务未能生成这批消息的总结。");
            }
            JSONObject message = choice.optJSONObject("message");
            Object content = message == null ? null : message.opt("content");
            String answer = "";
            if (content instanceof String) {
                answer = (String) content;
            } else if (content instanceof JSONArray) {
                StringBuilder text = new StringBuilder();
                JSONArray items = (JSONArray) content;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null && "text".equals(item.optString("type"))) {
                        text.append(item.optString("text"));
                    }
                }
                answer = text.toString();
            }
            answer = stripThinking(answer);
            if (answer.regionMatches(true, 0, "Error:", 0, 6)) {
                throw new SummaryException(serverError(status, answer));
            }
            if (answer.isEmpty()) {
                throw new SummaryException("模型没有返回可用的总结正文。请检查模型，或关闭思考模式后重试。");
            }
            if (stream) {
                throw new SummaryException("模型服务返回了普通 JSON，未提供 SSE 流式响应。请关闭流式模式后重试。");
            }
            return new Completion(answer, reportedModel(responseObject, config.apiKey));
        } finally {
            synchronized (this) {
                if (connection == current) {
                    connection = null;
                }
            }
            current.disconnect();
        }
    }

    private String readErrorBody(HttpURLConnection current, int request) throws IOException, CancelledException {
        // Only classify a bounded prefix. Never show, persist or log the server's error body.
        try (InputStream input = current.getErrorStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            if (input == null) {
                return "";
            }
            byte[] bytes = new byte[1024];
            while (buffer.size() < MAX_ERROR_BYTES) {
                checkActive(request);
                int read = input.read(bytes, 0, Math.min(bytes.length, MAX_ERROR_BYTES - buffer.size()));
                if (read == -1) {
                    break;
                }
                buffer.write(bytes, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String reportedModel(JSONObject response, String apiKey) {
        Object value = response.opt("model");
        if (!(value instanceof String)) {
            return null;
        }
        return safeReportedModel((String) value, apiKey);
    }

    private static String safeReportedModel(String value, String apiKey) {
        if (value == null) return null;
        String model = value.trim();
        if (model.isEmpty() || model.length() > 128 || (!apiKey.isEmpty() && model.contains(apiKey))) {
            return null;
        }
        for (int i = 0; i < model.length(); i++) {
            if (Character.isISOControl(model.charAt(i)) || Character.getType(model.charAt(i)) == Character.FORMAT) {
                return null;
            }
        }
        return model;
    }

    private static String describeError(Exception error) {
        if (error instanceof SummaryException || error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        if (error instanceof SocketTimeoutException) {
            return "模型服务响应超时。请确认模型已加载，关闭长思考模式，或减少消息数量后重试。";
        }
        if (error instanceof ConnectException) {
            return "模型服务未启动或端口拒绝连接。请先在 MNN Chat 启动 API 服务，并检查地址和端口。";
        }
        if (error instanceof UnknownHostException) {
            return "无法解析模型服务地址。请检查主机名或改用正确的 IP 地址。";
        }
        if (error instanceof JSONException) {
            return "模型服务返回的数据不符合 OpenAI Chat Completions 格式。";
        }
        return "无法连接模型 API。请检查服务、网络、地址和端口，以及 Android 是否允许该 HTTP/TLS 连接。";
    }

    private static String serverError(int status, String detail) {
        if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
            return httpError(status);
        }
        String lower = detail.toLowerCase(Locale.US);
        if (lower.contains("context_length_exceeded") || lower.contains("maximum context length")
                || lower.contains("context length exceeded") || lower.contains("context window") || lower.contains("prompt too long")
                || lower.contains("input too long") || lower.contains("上下文长度")) {
            return "输入超出模型上下文长度。请减少消息数量、缩短补充要求或使用支持更长上下文的模型。";
        }
        if (lower.contains("model_not_loaded") || lower.contains("model_not_found")
                || lower.contains("model not loaded") || lower.contains("no model loaded")
                || lower.contains("model is not loaded") || lower.contains("no model is loaded") || lower.contains("please load a model")
                || lower.contains("unknown model") || lower.contains("model does not exist")
                || (lower.contains("model") && lower.contains("not found"))
                || lower.contains("模型未加载") || lower.contains("未加载模型")) {
            return "模型尚未加载或模型名称不可用。请在 MNN Chat 中加载模型，并核对配置的模型名称。";
        }
        if (status >= 200 && status < 300) {
            return "模型服务已连接，但生成失败（HTTP 200 Error）。请在 MNN Chat 中检查模型和服务状态后重试。";
        }
        return httpError(status);
    }

    private static final class Completion {
        final String text;
        final String reportedModel;

        Completion(String text, String reportedModel) {
            this.text = text;
            this.reportedModel = reportedModel;
        }
    }

    static String stripThinking(String text) {
        String result = text.replaceAll("(?is)<think(?:\\s[^>]*)?>.*?</think>", "").trim();
        // An unfinished reasoning block has no final answer to display.
        if (result.toLowerCase(java.util.Locale.US).contains("<think>")) {
            return "";
        }
        int close = result.toLowerCase(java.util.Locale.US).lastIndexOf("</think>");
        return (close >= 0 ? result.substring(close + 8) : result).trim();
    }

    private static String httpError(int status) {
        if (status >= 300 && status < 400) {
            return "模型 API 返回重定向，已停止请求。请直接填写最终接口地址。";
        }
        if (status == 401 || status == 403) {
            return "模型 API 拒绝访问（HTTP " + status + "）。请检查 API Key 和服务权限。";
        }
        if (status == 404) {
            return "模型 API 不存在（HTTP 404）。请核对地址中的 /v1 路径和服务是否提供 Chat Completions。";
        }
        if (status == 400 || status == 413 || status == 422) {
            return "模型 API 拒绝请求（HTTP " + status + "）。请核对模型名称、上下文长度和 Chat Completions 参数兼容性。";
        }
        if (status == 429) {
            return "模型服务繁忙或触发请求限制（HTTP 429）。请稍后重试。";
        }
        return "模型服务请求失败（HTTP " + status + "）。请检查模型服务后重试。";
    }

    private static final class SummaryException extends Exception {
        SummaryException(String message) {
            super(message);
        }
    }

    private static final class CancelledException extends Exception {
    }
}
