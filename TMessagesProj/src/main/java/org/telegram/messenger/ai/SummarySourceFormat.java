/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact, lossless source records. Only generated headers occupy physical lines. */
final class SummarySourceFormat {
    private final List<SummaryMessage> messages;
    private final Map<String, Integer> indices = new HashMap<>();
    private final Map<Long, Integer> members = new HashMap<>();
    private final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd Z", Locale.US);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private SummarySourceFormat(List<SummaryMessage> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("没有可总结的文字消息。");
        this.messages = messages;
        for (int i = 0; i < messages.size(); i++) {
            SummaryMessage message = messages.get(i);
            if (message == null) throw new IllegalArgumentException("消息数据不完整，请重新加载。");
            requireUnicode(message.sender);
            requireUnicode(message.text);
            indices.put(message.dialogId + ":" + message.id, i);
            if (message.senderId != 0 && !members.containsKey(message.senderId)) {
                members.put(message.senderId, members.size() + 1);
            }
        }
    }

    static List<String> chunks(List<SummaryMessage> messages, int budget) {
        return new SummarySourceFormat(messages).chunks(budget);
    }

    private List<String> chunks(int budget) {
        ArrayList<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentDay = "";
        for (int i = 0; i < messages.size();) {
            int end = i + 1;
            String groupDay = date(messages.get(i));
            long groupLength = groupDay.length() + 1L + recordLength(i);
            while (end < messages.size()) {
                SummaryMessage next = messages.get(end);
                Integer parent = replyIndex(next);
                if (parent == null || parent < i || parent >= end
                        || next.date < messages.get(end - 1).date
                        || (long) next.date - messages.get(end - 1).date > 15 * 60) break;
                String nextDay = date(next);
                long added = recordLength(end) + (nextDay.equals(groupDay) ? 0 : nextDay.length() + 1L);
                if (groupLength + added > budget) break;
                groupLength += added;
                groupDay = nextDay;
                end++;
            }
            if (groupLength <= budget && current.length() > 0 && current.length() + groupLength > budget) {
                addChunk(chunks, current);
                currentDay = "";
            }
            for (int index = i; index < end; index++) {
                SummaryMessage message = messages.get(index);
                String recordDay = date(message);
                int offset = 0, part = 0;
                do {
                    String prefix = prefix(index, ++part);
                    int dayCost = recordDay.equals(currentDay) ? 0 : recordDay.length() + 1;
                    int overhead = dayCost + prefix.length() + 3; // two body quotes and LF
                    if (current.length() > 0 && budget - current.length() - overhead < 12) {
                        addChunk(chunks, current);
                        currentDay = "";
                        dayCost = recordDay.length() + 1;
                        overhead = dayCost + prefix.length() + 3;
                    }
                    if (overhead + 12 > budget) {
                        throw new IllegalArgumentException("消息元数据超过当前请求预算，请提高上下文字符预算或缩短补充要求。");
                    }
                    int available = budget - current.length() - overhead;
                    int textEnd = offset, used = 0;
                    while (textEnd < message.text.length()) {
                        int point = message.text.codePointAt(textEnd);
                        int cost = escapedWidth(point);
                        if (used + cost > available) break;
                        used += cost;
                        textEnd += Character.charCount(point);
                    }
                    if (dayCost != 0) current.append(recordDay).append('\n');
                    currentDay = recordDay;
                    current.append(prefix).append(quote(message.text.substring(offset, textEnd))).append('\n');
                    offset = textEnd;
                    if (offset < message.text.length()) {
                        addChunk(chunks, current);
                        currentDay = "";
                    }
                } while (offset < message.text.length());
            }
            i = end;
        }
        if (current.length() > 0) addChunk(chunks, current);
        return chunks;
    }

    private long recordLength(int index) {
        String text = messages.get(index).text;
        long length = prefix(index, 1).length() + 3L;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            length += escapedWidth(point);
            i += Character.charCount(point);
        }
        return length;
    }

    private String date(SummaryMessage message) {
        return "日期 " + day.format(new Date(message.date * 1000L));
    }

    private Integer replyIndex(SummaryMessage message) {
        if (message.replyToId <= 0 || message.replyToDialogId == 0) return null;
        return indices.get(message.replyToDialogId + ":" + message.replyToId);
    }

    private String prefix(int index, int part) {
        SummaryMessage message = messages.get(index);
        StringBuilder out = new StringBuilder("[m").append(index + 1).append("] ")
                .append(clock.format(new Date(message.date * 1000L))).append(' ')
                .append(message.senderId == 0 ? "身份未知" : "成员" + members.get(message.senderId))
                .append('(').append(quote(message.sender)).append(')');
        if (part > 1) out.append(" 续").append(part);
        if (message.outgoing) out.append(" 本人发言");
        if (message.mentionedSelf) out.append(" 明确提及本人");
        if (message.replyToSelfKnown) out.append(message.replyToSelf ? " 回复本人" : " 回复非本人");
        Integer reply = replyIndex(message);
        if (reply != null) out.append(" 回复[m").append(reply + 1).append(']');
        else if (message.replyToId > 0) {
            out.append(message.replyToDialogId == 0 ? " 回复未知会话消息(" : " 回复未收录消息(")
                    .append(message.replyToDialogId).append(':').append(message.replyToId).append(')');
        } else out.append(" 说");
        return out.append(": ").toString();
    }

    private static int escapedWidth(int point) {
        if (point == '"' || point == '\\' || point == '\n' || point == '\r'
                || point == '\t' || point == '\b' || point == '\f') return 2;
        return point < 0x20 || point == 0x85 || point == 0x2028 || point == 0x2029 ? 6 : Character.charCount(point);
    }

    /** Separate from the legacy JSONL encoder used by question and merge prompts. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x85 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("消息包含无效的 Unicode 字符，请重新加载。");
                }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("消息包含无效的 Unicode 字符，请重新加载。");
        }
    }

    private static void addChunk(List<String> chunks, StringBuilder current) {
        if (chunks.size() >= AiSummaryPrompt.MAX_SOURCE_CHUNKS) {
            throw new IllegalArgumentException("消息文字量超过本次总结上限（64 个分段）。请减少最近消息条数或缩小时间范围。");
        }
        chunks.add(current.toString());
        current.setLength(0);
    }
}
