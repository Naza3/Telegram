/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** OpenAI SSE framing and incremental reasoning suppression; no Android or transport state. */
final class AiSummarySse {
    // Bounded workers and queue: stalled sockets cannot retain an unbounded number of requests.
    private static final ThreadPoolExecutor USAGE_TAIL_READERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), action -> {
                Thread thread = new Thread(action, "ai-summary-usage-tail");
                thread.setDaemon(true);
                return thread;
            });
    private static final Pattern THINK_TAG = Pattern.compile("</?think(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    interface Cancellation {
        void check() throws IOException;
    }

    interface Listener {
        void onText(String accumulatedText);
        /** Counts received content/reasoning characters, never exposes reasoning text. */
        default void onContent(int receivedCharacters, boolean reasoningObserved) { }
        /** One request's cumulative snapshot; complete becomes true only at a clean stream boundary. */
        default void onUsage(UsageStats usage, boolean complete) { }
        /** The answer is already truncated; only a bounded usage tail remains useful. */
        default void onTailReadAbandoned() { }
        /** Runs after the abandoned read has actually released its stream lock. */
        default void onAbandonedReadClosed() { }
    }

    static final class Result {
        final String text;
        final String reportedModel;

        Result(String text, String reportedModel) {
            this.text = text;
            this.reportedModel = reportedModel;
        }
    }

    static final class Failure extends IOException {
        final String serverDetail;
        final boolean outputLimit;
        final boolean reasoningObserved;

        Failure(String safeMessage) {
            this(safeMessage, null, false, false);
        }

        Failure(String safeMessage, String serverDetail) {
            this(safeMessage, serverDetail, false, false);
        }

        Failure(String safeMessage, String serverDetail, boolean outputLimit, boolean reasoningObserved) {
            super(safeMessage);
            this.serverDetail = serverDetail;
            this.outputLimit = outputLimit;
            this.reasoningObserved = reasoningObserved;
        }
    }

    /** Ordinary completions must apply the same reasoning boundaries as streamed completions. */
    static String stripThinking(String content) throws Failure {
        VisibleText text = new VisibleText();
        text.append(content);
        return text.finish();
    }

    static Result read(InputStream input, int byteLimit, int usageTailTimeoutMs, Cancellation cancellation, Listener listener)
            throws IOException, JSONException {
        InputStream bounded = new FilterInputStream(input) {
            private int count;

            @Override
            public int read() throws IOException {
                cancellation.check();
                int value = in.read();
                if (value != -1) account(1);
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                cancellation.check();
                int read = in.read(bytes, offset, Math.min(length, Math.max(1, byteLimit - count + 1)));
                if (read > 0) account(read);
                return read;
            }

            private void account(int amount) throws Failure {
                count += amount;
                if (count > byteLimit) throw new Failure("模型流式响应超过大小上限，已停止处理。请减少输出长度后重试。");
            }
        };
        State state = new State(listener);
        TailLines reader = new TailLines(new BufferedReader(new InputStreamReader(bounded,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))), listener, usageTailTimeoutMs);
        try {
            StringBuilder data = new StringBuilder();
            String event = "";
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine(state.outputLimit)) != null) {
                cancellation.check();
                if (firstLine && line.startsWith("\ufeff")) line = line.substring(1);
                firstLine = false;
                if (line.isEmpty()) {
                    if (data.length() > 0 && state.event(data.toString(), event)) break;
                    data.setLength(0);
                    event = "";
                } else if (!line.startsWith(":")) {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if ("data".equals(field)) data.append(value).append('\n');
                    else if ("event".equals(field)) event = value;
                }
            }
            if (!state.done && data.length() > 0) state.event(data.toString(), event);
            if (!state.done && !state.finished) {
                throw new Failure("流式连接提前中断，未收到完整结束标记。请重试或关闭流式模式。");
            }
            listener.onUsage(state.usage, true);
            if (state.outputLimit) {
                throw new Failure("模型输出达到长度上限，结果不完整。", null, true, state.reasoningObserved);
            }
            String text = state.text.finish();
            if (text.isEmpty()) throw new Failure("模型没有返回可用的总结正文。请关闭思考模式后重试。");
            return new Result(text, state.model);
        } catch (IOException | JSONException error) {
            if (state.outputLimit) {
                cancellation.check();
                // A broken usage tail cannot turn a known truncated answer into a generic failure.
                throw new Failure("模型输出达到长度上限，结果不完整。", null, true, state.reasoningObserved);
            }
            throw error;
        } finally {
            reader.close();
        }
    }

    /** Reads only the already-truncated response's tail with one absolute, bounded deadline. */
    private static final class TailLines {
        final BufferedReader reader;
        final Listener listener;
        final int timeoutMs;
        long deadline;
        boolean deferredClose;

        TailLines(BufferedReader reader, Listener listener, int timeoutMs) {
            this.reader = reader;
            this.listener = listener;
            this.timeoutMs = timeoutMs;
        }

        String readLine(boolean outputLimit) throws IOException {
            if (!outputLimit) return reader.readLine();
            if (deadline == 0) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new InterruptedIOException("Usage tail deadline");
            TailRead operation = new TailRead(reader, listener);
            Future<String> future;
            try { future = USAGE_TAIL_READERS.submit(operation); }
            catch (RejectedExecutionException busy) { throw new InterruptedIOException("Usage tail readers busy"); }
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | InterruptedException stopped) {
                // Closing a BufferedReader or HttpURLConnection while read() holds its lock can
                // block. The same bounded worker closes it once its socket read finally returns.
                deferredClose = true;
                listener.onTailReadAbandoned();
                operation.abandon();
                future.cancel(true);
                if (future instanceof Runnable) USAGE_TAIL_READERS.remove((Runnable) future);
                if (stopped instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new InterruptedIOException("Usage tail interrupted");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("Usage tail read failed");
            }
        }

        void close() throws IOException {
            if (!deferredClose) reader.close();
        }
    }

    private static final class TailRead implements Callable<String> {
        final BufferedReader reader;
        final Listener listener;
        boolean started, finished, abandoned;

        TailRead(BufferedReader reader, Listener listener) {
            this.reader = reader;
            this.listener = listener;
        }

        @Override public String call() throws IOException {
            synchronized (this) {
                started = true;
                if (abandoned) return null;
            }
            try { return reader.readLine(); }
            finally {
                boolean close;
                synchronized (this) { finished = true; close = abandoned; }
                if (close) cleanup();
            }
        }

        void abandon() {
            boolean close;
            synchronized (this) { abandoned = true; close = !started || finished; }
            if (close) cleanup();
        }

        void cleanup() {
            try { reader.close(); } catch (IOException ignored) { }
            finally { listener.onAbandonedReadClosed(); }
        }
    }

    private static final class State {
        final VisibleText text = new VisibleText();
        final Listener listener;
        boolean done;
        boolean finished;
        boolean outputLimit;
        UsageStats usage = UsageStats.UNKNOWN_USAGE;
        String model;
        String lastVisible = "";
        int receivedCharacters;
        boolean reasoningObserved;

        State(Listener listener) {
            this.listener = listener;
        }

        boolean event(String data, String event) throws JSONException, Failure {
            String value = data.trim();
            if (value.isEmpty()) return false;
            if ("error".equalsIgnoreCase(event)) {
                throw new Failure("模型流式服务返回错误。", value);
            }
            if ("[DONE]".equals(value)) {
                done = true;
                return true;
            }
            JSONObject object = new JSONObject(value);
            if (object.has("error") && !object.isNull("error")) {
                throw new Failure("模型流式服务返回错误。", object.get("error").toString());
            }
            if (object.opt("model") instanceof String) model = object.getString("model");
            if (object.has("usage") && !object.isNull("usage")) {
                // OpenAI stream usage is cumulative for this request, not another batch of tokens.
                usage = UsageStats.fromJson(object.optJSONObject("usage"));
                listener.onUsage(usage, false);
            }
            JSONArray choices = object.optJSONArray("choices");
            if (choices == null) throw new Failure("流式响应缺少 choices。请确认服务兼容 SSE，或关闭流式模式。");
            if (choices.length() == 0) return false; // Optional usage-only event.
            JSONObject choice = choices.getJSONObject(0);
            String finish = choice.optString("finish_reason", "");
            if ("content_filter".equals(finish)) throw new Failure("模型服务未能生成这批消息的总结。");
            boolean outputLimit = "length".equals(finish);
            if (!finish.isEmpty() && !"null".equals(finish) && !"stop".equals(finish) && !outputLimit) {
                throw new Failure("模型流式输出未正常结束。请关闭流式模式或更换模型后重试。");
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null && !"stop".equals(finish) && !outputLimit) {
                throw new Failure("流式响应缺少 delta。请确认服务兼容 SSE，或关闭流式模式。");
            }
            String content = delta == null ? "" : textContent(delta.opt("content"));
            int reasoningCharacters = delta == null ? 0 : reasoningCharacters(delta);
            if (finished && (!content.isEmpty() || reasoningCharacters > 0)) {
                throw new Failure("模型在完成标记之后继续输出内容，响应格式异常。");
            }
            if (reasoningCharacters > 0) reasoningObserved = true;
            if (!content.isEmpty()) {
                try {
                    text.append(content);
                } catch (Failure failure) {
                    if (!outputLimit) throw failure;
                }
            }
            reasoningObserved |= text.reasoningObserved;
            if (!content.isEmpty() || reasoningCharacters > 0) {
                receivedCharacters += content.length() + reasoningCharacters;
                listener.onContent(receivedCharacters, reasoningObserved);
            }
            if (!content.isEmpty()) {
                String visible = text.intermediate();
                if (!visible.isEmpty() && !visible.equals(lastVisible)) {
                    lastVisible = visible;
                    listener.onText(visible);
                }
            }
            if (outputLimit) {
                this.outputLimit = true;
            }
            if ("stop".equals(finish) || outputLimit) finished = true;
            return false;
        }

    }

    static int reasoningCharacters(JSONObject message) {
        return textContent(message.opt("reasoning_content")).length()
                + textContent(message.opt("reasoning")).length();
    }

    static boolean containsThinkingTag(String content) {
        return THINK_TAG.matcher(content).find();
    }

    static String textContent(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder text = new StringBuilder();
            JSONArray parts = (JSONArray) value;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) text.append(part.optString("text"));
            }
            return text.toString();
        }
        return "";
    }

    /** Buffers incomplete think tags, including tags split across SSE events or UTF-8 reads. */
    private static final class VisibleText {
        final StringBuilder visible = new StringBuilder();
        final StringBuilder tag = new StringBuilder();
        int thinkingDepth;
        boolean closedThinking;
        boolean reasoningObserved;

        void append(String value) throws Failure {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (tag.length() == 0 && c != '<') {
                    if (thinkingDepth == 0) visible.append(c);
                    continue;
                }
                tag.append(c);
                while (tag.length() > 0) {
                    String lower = tag.toString().toLowerCase(Locale.US);
                    if ("<think>".equals(lower) || (attributes(lower, "<think") && lower.endsWith(">"))) {
                        reasoningObserved = true;
                        if (thinkingDepth == 0 && !trustedHeading(visible.toString())) visible.setLength(0);
                        thinkingDepth++;
                        tag.setLength(0);
                        break;
                    }
                    if ("</think>".equals(lower) || (attributes(lower, "</think") && lower.endsWith(">"))) {
                        reasoningObserved = true;
                        if (thinkingDepth > 0) thinkingDepth--;
                        else visible.setLength(0); // Some servers omit the initial opening think token.
                        closedThinking = true;
                        tag.setLength(0);
                        break;
                    }
                    boolean candidate = "<think>".startsWith(lower) || "</think>".startsWith(lower)
                            || attributes(lower, "<think") || attributes(lower, "</think");
                    if (candidate) {
                        if (tag.length() > 512) throw new Failure("模型思考标签格式异常，请关闭思考模式后重试。");
                        break;
                    }
                    if (thinkingDepth == 0) visible.append(tag.charAt(0));
                    tag.deleteCharAt(0);
                }
            }
        }

        String intermediate() {
            String value = visible.toString().trim();
            // Hold an unmarked preamble until an answer heading or a reasoning boundary appears.
            return closedThinking || trustedHeading(value) ? value : "";
        }

        String finish() throws Failure {
            if (thinkingDepth != 0 || tag.length() > 1) {
                throw new Failure("模型只返回了未完成的思考内容，请关闭思考模式或提高输出上限后重试。");
            }
            if (tag.length() == 1) visible.append(tag);
            return visible.toString().trim();
        }

        private static boolean trustedHeading(String value) {
            String text = value.trim();
            return text.startsWith("【话题】") || text.startsWith("【结论】") || text.startsWith("【待办】")
                    || text.startsWith("【回答】") || text.startsWith("## 话题") || text.startsWith("### 话题");
        }

        private static boolean attributes(String value, String tag) {
            return value.startsWith(tag) && value.length() > tag.length()
                    && Character.isWhitespace(value.charAt(tag.length()));
        }
    }
}
