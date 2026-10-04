/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lossless text with self-contained alias dictionaries, without message IDs or individual times. */
final class SummarySourceFormat {
    static final String RECORDS_MARKER = "\n对话：\n";
    private final List<SummaryMessage> messages;
    private final Map<String, Integer> indices = new HashMap<>();
    private final List<String> aliases = new ArrayList<>();
    private final Map<String, LinkedHashSet<String>> allNames = new HashMap<>();
    private final String range;

    private SummarySourceFormat(List<SummaryMessage> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("没有可总结的文字消息。");
        this.messages = messages;
        Map<Long, String> members = new HashMap<>();
        int nextAlias = 0, first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            SummaryMessage message = messages.get(i);
            if (message == null) throw new IllegalArgumentException("消息数据不完整，请重新加载。");
            requireUnicode(message.sender);
            requireUnicode(message.text);
            indices.put(message.dialogId + ":" + message.id, i);
            String alias = message.senderId == 0 ? null : members.get(message.senderId);
            if (alias == null) {
                alias = alias(nextAlias++);
                if (message.senderId != 0) members.put(message.senderId, alias);
            }
            aliases.add(alias);
            String nameKey = alias + (message.senderId == 0 ? "?" : "");
            LinkedHashSet<String> knownNames = allNames.get(nameKey);
            if (knownNames == null) { knownNames = new LinkedHashSet<>(); allNames.put(nameKey, knownNames); }
            // The final dictionary name is the most recently observed one, including A -> B -> A.
            knownNames.remove(message.sender);
            knownNames.add(message.sender);
            first = Math.min(first, message.date);
            last = Math.max(last, message.date);
        }
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        range = "消息时间：" + time.format(new Date(first * 1000L)) + " 至 "
                + time.format(new Date(last * 1000L)) + "\n";
    }

    static List<String> chunks(List<SummaryMessage> messages, int budget) {
        return new SummarySourceFormat(messages).chunks(budget);
    }

    private List<String> chunks(int budget) {
        ArrayList<String> chunks = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        LinkedHashMap<String, LinkedHashSet<String>> names = new LinkedHashMap<>();
        for (int i = 0; i < messages.size();) {
            int end = i + 1;
            LinkedHashMap<String, LinkedHashSet<String>> groupNames = addNames(new LinkedHashMap<>(), i);
            long groupRows = recordLength(i);
            while (end < messages.size()) {
                SummaryMessage next = messages.get(end);
                Integer parent = replyIndex(next);
                if (parent == null || parent < i || parent >= end
                        || next.date < messages.get(end - 1).date
                        || (long) next.date - messages.get(end - 1).date > 15 * 60) break;
                LinkedHashMap<String, LinkedHashSet<String>> candidate = addNames(groupNames, end);
                long added = recordLength(end);
                if (header(candidate).length() + groupRows + added > budget) break;
                groupNames = candidate;
                groupRows += added;
                end++;
            }
            if (rows.length() > 0 && header(groupNames).length() + groupRows <= budget) {
                LinkedHashMap<String, LinkedHashSet<String>> combined = names;
                for (int index = i; index < end; index++) combined = addNames(combined, index);
                if (header(combined).length() + rows.length() + groupRows > budget) {
                    addChunk(chunks, names, rows);
                    names.clear();
                }
            }
            for (int index = i; index < end; index++) {
                SummaryMessage message = messages.get(index);
                int offset = 0;
                do {
                    String prefix = prefix(index, offset > 0);
                    LinkedHashMap<String, LinkedHashSet<String>> candidate = addNames(names, index);
                    int overhead = header(candidate).length() + prefix.length() + 3; // quoted body and LF
                    if (rows.length() > 0 && budget - rows.length() - overhead < 12) {
                        addChunk(chunks, names, rows);
                        names.clear();
                        candidate = addNames(names, index);
                        overhead = header(candidate).length() + prefix.length() + 3;
                    }
                    if (overhead + 12 > budget) {
                        throw new IllegalArgumentException("消息昵称与必要关系信息超过当前请求预算，请提高上下文字符预算或缩短补充要求。");
                    }
                    int available = budget - rows.length() - overhead;
                    int textEnd = offset, used = 0;
                    while (textEnd < message.text.length()) {
                        int point = message.text.codePointAt(textEnd);
                        int cost = escapedWidth(point);
                        if (used + cost > available) break;
                        used += cost;
                        textEnd += Character.charCount(point);
                    }
                    names = candidate;
                    rows.append(prefix).append(quote(message.text.substring(offset, textEnd))).append('\n');
                    offset = textEnd;
                    if (offset < message.text.length()) {
                        addChunk(chunks, names, rows);
                        names.clear();
                    }
                } while (offset < message.text.length());
            }
            i = end;
        }
        if (rows.length() > 0) addChunk(chunks, names, rows);
        return chunks;
    }

    private LinkedHashMap<String, LinkedHashSet<String>> addNames(Map<String, LinkedHashSet<String>> previous, int index) {
        LinkedHashMap<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : previous.entrySet()) {
            result.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        addName(result, index);
        Integer parent = replyIndex(messages.get(index));
        if (parent != null) addName(result, parent);
        return result;
    }

    private void addName(Map<String, LinkedHashSet<String>> names, int index) {
        SummaryMessage message = messages.get(index);
        String key = aliases.get(index) + (message.senderId == 0 ? "?" : "");
        LinkedHashSet<String> values = names.get(key);
        if (values == null) { values = new LinkedHashSet<>(); names.put(key, values); }
        // Keep every observed name for this identity, including across separate model requests.
        values.addAll(allNames.get(key));
    }

    private String header(Map<String, LinkedHashSet<String>> names) {
        StringBuilder out = new StringBuilder(range).append("成员：");
        boolean first = true;
        for (Map.Entry<String, LinkedHashSet<String>> entry : names.entrySet()) {
            if (!first) out.append("; ");
            first = false;
            out.append(entry.getKey()).append('=');
            if (entry.getValue().size() > 1) out.append('[');
            boolean firstName = true;
            for (String name : entry.getValue()) {
                if (!firstName) out.append(',');
                firstName = false;
                out.append(quote(name));
            }
            if (entry.getValue().size() > 1) out.append(']');
        }
        return out.append(RECORDS_MARKER).toString();
    }

    private long recordLength(int index) {
        String text = messages.get(index).text;
        long length = prefix(index, false).length() + 3L;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            length += escapedWidth(point);
            i += Character.charCount(point);
        }
        return length;
    }

    private Integer replyIndex(SummaryMessage message) {
        if (message.replyToId <= 0 || message.replyToDialogId == 0) return null;
        return indices.get(message.replyToDialogId + ":" + message.replyToId);
    }

    private String prefix(int index, boolean continuation) {
        SummaryMessage message = messages.get(index);
        StringBuilder out = new StringBuilder(aliases.get(index));
        Integer parent = replyIndex(message);
        if (parent != null) out.append(" @").append(aliases.get(parent));
        else if (message.replyToId > 0) out.append(message.replyToDialogId == 0 ? " @目标未知" : " @未收录");
        if (continuation) out.append("（续片）");
        if (message.outgoing) out.append("（本人发言）");
        if (message.mentionedSelf) out.append("（明确提及本人）");
        if (message.replyToSelfKnown) out.append(message.replyToSelf ? "（回复本人）" : "（回复非本人）");
        return out.append(": ").toString();
    }

    private static String alias(int value) {
        StringBuilder out = new StringBuilder();
        do { out.append((char) ('A' + value % 26)); value = value / 26 - 1; } while (value >= 0);
        return out.reverse().toString();
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

    private void addChunk(List<String> chunks, Map<String, LinkedHashSet<String>> names, StringBuilder rows) {
        if (chunks.size() >= AiSummaryPrompt.MAX_SOURCE_CHUNKS) {
            throw new IllegalArgumentException("消息文字量超过本次总结上限（64 个分段）。请减少最近消息条数或缩小时间范围。");
        }
        chunks.add(header(names) + rows);
        rows.setLength(0);
    }
}
