/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Non-streaming, cancellable OpenAI-compatible chat completions transport. */
public final class AiSummaryClient {
    public interface Callback {
        void onSuccess(String summary);
        void onError(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 300_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_MODEL_REQUESTS = 128;
    private int generation;
    private Future<?> task;
    private HttpURLConnection connection;

    public synchronized void summarize(AiSummarySettings.Config config, List<SummaryMessage> messages, Callback callback) {
        cancel();
        final int request = generation;
        final ArrayList<SummaryMessage> snapshot = messages == null ? null : new ArrayList<>(messages);
        task = EXECUTOR.submit(() -> {
            try {
                String error = AiSummarySettings.validate(config);
                if (error != null) {
                    throw new SummaryException(error);
                }
                List<String> chunks = AiSummaryPrompt.sourceChunks(snapshot);
                int requestCount = chunks.size();
                List<String> summaries = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    checkActive(request);
                    summaries.add(complete(config, AiSummaryPrompt.sourcePrompt(chunks.get(i), i + 1, chunks.size()), request));
                }
                while (summaries.size() > 1) {
                    checkActive(request);
                    List<String> mergeChunks = AiSummaryPrompt.mergeChunks(summaries);
                    requestCount += mergeChunks.size();
                    if (requestCount > MAX_MODEL_REQUESTS) {
                        throw new SummaryException("分段摘要无法在本次请求上限内完成合并。请减少消息数量后重试。");
                    }
                    List<String> merged = new ArrayList<>();
                    for (String chunk : mergeChunks) {
                        merged.add(complete(config, AiSummaryPrompt.mergePrompt(chunk), request));
                    }
                    summaries = merged;
                }
                String result = summaries.get(0);
                AiSummaryPrompt.validateReferences(result, snapshot.size());
                deliver(request, () -> callback.onSuccess(result));
            } catch (CancelledException ignored) {
                // Cancellation is not an error and never delivers a stale callback.
            } catch (SummaryException | IllegalArgumentException e) {
                deliver(request, () -> callback.onError(e.getMessage()));
            } catch (SocketTimeoutException e) {
                deliver(request, () -> callback.onError("模型服务响应超时。请确认模型已加载，或减少消息数量后重试。"));
            } catch (IOException e) {
                deliver(request, () -> callback.onError("无法连接模型 API。请检查服务已启动、地址和端口正确，以及 Android 是否允许该 HTTP 连接。"));
            } catch (JSONException e) {
                deliver(request, () -> callback.onError("模型服务返回的数据不符合 OpenAI Chat Completions 格式。"));
            } finally {
                synchronized (AiSummaryClient.this) {
                    if (request == generation) {
                        task = null;
                    }
                }
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
            connection.disconnect();
            connection = null;
        }
    }

    private synchronized void checkActive(int request) throws CancelledException {
        if (request != generation || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
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

    private String complete(AiSummarySettings.Config config, String prompt, int request)
            throws IOException, JSONException, SummaryException, CancelledException {
        checkActive(request);
        JSONObject body = new JSONObject();
        if (!config.model.isEmpty()) {
            body.put("model", config.model);
        }
        body.put("stream", false);
        body.put("temperature", 0.2);
        // Some MNN Chat releases accept but ignore this compatibility parameter.
        body.put("max_tokens", config.maxOutputTokens);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", AiSummaryPrompt.SYSTEM_PROMPT))
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
            current.setConnectTimeout(CONNECT_TIMEOUT_MS);
            current.setReadTimeout(READ_TIMEOUT_MS);
            current.setUseCaches(false);
            current.setDoOutput(true);
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            current.setRequestProperty("Accept", "application/json");
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
                throw new SummaryException(httpError(status));
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
            JSONArray choices = new JSONObject(response).optJSONArray("choices");
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
            if (answer.startsWith("Error:")) {
                throw new SummaryException("MNN 模型生成失败。请在 MNN Chat 中检查当前模型已加载及服务状态，再重新总结。");
            }
            if (answer.isEmpty()) {
                throw new SummaryException("模型没有返回可用的总结正文。请检查模型，或关闭思考模式后重试。");
            }
            return answer;
        } finally {
            synchronized (this) {
                if (connection == current) {
                    connection = null;
                }
            }
            current.disconnect();
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
