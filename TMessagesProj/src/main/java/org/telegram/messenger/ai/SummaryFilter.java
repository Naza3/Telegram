/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Deterministic selection from an already loaded snapshot. It never fetches more history. */
public final class SummaryFilter {
    public enum Mode { ALL, FOCUS_SELF, FILTER_SELF }

    public static final class Options {
        public static final Options DEFAULT = new Options(Mode.ALL, 0, "");
        public final Mode mode;
        /** Telegram peer ID, including negative anonymous/channel sender IDs; zero means any. */
        public final long senderId;
        public final String keyword;

        public Options(Mode mode, long senderId, String keyword) {
            this.mode = mode == null ? Mode.ALL : mode;
            this.senderId = senderId;
            this.keyword = keyword == null ? "" : keyword.trim();
            if (this.keyword.codePointCount(0, this.keyword.length()) > 128) {
                throw new IllegalArgumentException("关键词最多 128 个字符。");
            }
        }

        /** True even if every source happens to match: filtered tasks must not advance a cursor. */
        public boolean hasFilters() {
            return mode == Mode.FILTER_SELF || senderId != 0 || !keyword.isEmpty();
        }
    }

    public static final class Result {
        public final ArrayList<SummaryMessage> messages;
        public final int matchedCount;
        public final int contextCount;
        public final boolean filtered;
        public final String coverageNote;

        private Result(ArrayList<SummaryMessage> messages, int matchedCount, int contextCount,
                boolean filtered, String coverageNote) {
            this.messages = new ArrayList<>(messages);
            this.matchedCount = matchedCount;
            this.contextCount = contextCount;
            this.filtered = filtered;
            this.coverageNote = coverageNote;
        }
    }

    private SummaryFilter() { }

    /** ownerId must be the same real Telegram account identity used to load this snapshot. */
    public static Result apply(List<SummaryMessage> messages, long ownerId, Options options) {
        if (messages == null) {
            throw new IllegalArgumentException("缺少本次消息快照。");
        }
        Options config = options == null ? Options.DEFAULT : options;
        if (config.mode == Mode.FILTER_SELF && ownerId <= 0) {
            throw new IllegalArgumentException("无法确认当前账号身份，不能筛选与我相关的消息。");
        }

        // SummaryMessage is immutable. Copy the list and keep the first occurrence of a source
        // identity so repeated context references never duplicate a message or a task.
        LinkedHashMap<SourceKey, SummaryMessage> unique = new LinkedHashMap<>();
        for (SummaryMessage message : new ArrayList<>(messages)) {
            if (message == null) {
                throw new IllegalArgumentException("消息快照包含无效来源，请重新读取。");
            }
            SourceKey key = new SourceKey(message.dialogId, message.id);
            if (!unique.containsKey(key)) {
                unique.put(key, message);
            }
        }
        ArrayList<SummaryMessage> snapshot = new ArrayList<>(unique.values());
        HashMap<SourceKey, Integer> indices = new HashMap<>();
        for (int i = 0; i < snapshot.size(); i++) {
            SummaryMessage message = snapshot.get(i);
            indices.put(new SourceKey(message.dialogId, message.id), i);
        }

        String keyword = config.keyword.toLowerCase(Locale.ROOT);
        BitSet matches = new BitSet(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            SummaryMessage message = snapshot.get(i);
            boolean selfMatches = config.mode != Mode.FILTER_SELF
                    || message.senderId == ownerId
                    || message.mentionedSelf
                    || (message.replyToSelfKnown && message.replyToSelf);
            boolean senderMatches = config.senderId == 0 || message.senderId == config.senderId;
            boolean keywordMatches = keyword.isEmpty()
                    || message.text.toLowerCase(Locale.ROOT).contains(keyword);
            if (selfMatches && senderMatches && keywordMatches) {
                matches.set(i);
            }
        }

        BitSet selected = (BitSet) matches.clone();
        if (config.mode == Mode.FILTER_SELF) {
            for (int i = matches.nextSetBit(0); i >= 0; i = matches.nextSetBit(i + 1)) {
                SummaryMessage message = snapshot.get(i);
                // Neighbor context is bounded to one source on each side and cannot cross chats.
                if (i > 0 && snapshot.get(i - 1).dialogId == message.dialogId) {
                    selected.set(i - 1);
                }
                if (i + 1 < snapshot.size() && snapshot.get(i + 1).dialogId == message.dialogId) {
                    selected.set(i + 1);
                }
                // A reliable, same-chat reply parent can be elsewhere in the already loaded range.
                // Do not follow replies recursively or fetch missing parents from the network.
                if (message.replyToId > 0 && message.replyToDialogId == message.dialogId) {
                    Integer parent = indices.get(new SourceKey(message.dialogId, message.replyToId));
                    if (parent != null) {
                        selected.set(parent);
                    }
                }
            }
        }

        ArrayList<SummaryMessage> selectedMessages = new ArrayList<>();
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
            selectedMessages.add(snapshot.get(i));
        }
        int matched = matches.cardinality();
        int context = selected.cardinality() - matched;
        boolean filtered = config.hasFilters();
        StringBuilder note = new StringBuilder();
        if (config.mode == Mode.FOCUS_SELF) {
            note.append("总结方向突出与我相关的内容；该方向本身保留全部范围内文字。 ");
        } else if (config.mode == Mode.FILTER_SELF) {
            note.append("与我相关匹配：明确提及本人、已确认回复本人，以及本人身份可确认的发言。 ");
        }
        if (config.senderId != 0) {
            note.append("发言者 ID = ").append(config.senderId).append("。 ");
        }
        if (!config.keyword.isEmpty()) {
            note.append("正文关键词按不区分大小写的字面子串匹配；与其他条件同时满足。 ");
        }
        note.append("本次范围内 ").append(snapshot.size()).append(" 条唯一文字，匹配 ")
                .append(matched).append(" 条");
        if (config.mode == Mode.FILTER_SELF) {
            note.append("，补充 ").append(context)
                    .append(" 条同范围上下文（相邻前后各至多一条及明确回复父消息）；上下文可不满足筛选条件");
        }
        note.append("，实际发送 ").append(selectedMessages.size()).append(" 条。");
        if (filtered) {
            note.append(" 此筛选结果不推进通用增量游标。");
        }
        if (config.mode != Mode.ALL) {
            note.append(" 没有明确提及或缺少回复关系，不能据此判定其他讨论与我无关。");
        }
        return new Result(selectedMessages, matched, context, filtered, note.toString());
    }

    private static final class SourceKey {
        final long dialogId;
        final int messageId;

        SourceKey(long dialogId, int messageId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof SourceKey)) {
                return false;
            }
            SourceKey other = (SourceKey) object;
            return dialogId == other.dialogId && messageId == other.messageId;
        }

        @Override
        public int hashCode() {
            return 31 * (int) (dialogId ^ (dialogId >>> 32)) + messageId;
        }
    }
}
