/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Complete, immutable send plan over text that has already been parsed once.
 * This class never normalizes text, parses Markdown, or mutates native entities.
 * All positions and limits use UTF-16 code units, as Telegram entities do.
 */
public final class SummaryPublishPlan {
    public static final int MAX_PARTS = 64;

    public final String normalizedText;
    public final List<Part> parts;
    public final int maxLength;

    /** A native entity's source-list index and its end-exclusive text range. */
    public static final class EntityRange {
        public final int sourceIndex;
        public final int start;
        public final int end;
        public final boolean atomic;

        /** sourceIndex=-1 is a protected interval with no outgoing entity. */
        public EntityRange(int sourceIndex, int start, int end, boolean atomic) {
            if (sourceIndex < -1 || start < 0 || end <= start) {
                throw new IllegalArgumentException("Invalid entity range");
            }
            if (sourceIndex == -1 && !atomic) {
                throw new IllegalArgumentException("A protection-only range must be atomic");
            }
            this.sourceIndex = sourceIndex;
            this.start = start;
            this.end = end;
            this.atomic = atomic;
        }
    }

    /** Clone the indicated native entity and replace only its offset/length. */
    public static final class EntitySlice {
        public final int sourceIndex;
        public final int offset;
        public final int length;

        private EntitySlice(int sourceIndex, int offset, int length) {
            this.sourceIndex = sourceIndex;
            this.offset = offset;
            this.length = length;
        }
    }

    public static final class Part {
        public final int start;
        public final int end;
        public final String text;
        public final List<EntitySlice> entities;

        private Part(int start, int end, String text, List<EntitySlice> entities) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
        }
    }

    private SummaryPublishPlan(String normalizedText, List<Part> parts, int maxLength) {
        this.normalizedText = normalizedText;
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        this.maxLength = maxLength;
    }

    /**
     * Computes every part before returning. Styles may be clipped across parts;
     * atomic entities (including overlapping atoms) must remain in one part.
     * No partial plan is returned for invalid ranges, oversized atoms/clusters,
     * or a result exceeding maxParts. Empty input produces no outgoing parts.
     */
    public static SummaryPublishPlan create(String parsedText, List<EntityRange> entities,
            int maxUtf16, int maxParts) {
        if (parsedText == null || entities == null) {
            throw new IllegalArgumentException("Text and entity ranges must not be null");
        }
        if (maxUtf16 <= 0 || maxParts < 1 || maxParts > MAX_PARTS) {
            throw new IllegalArgumentException("Invalid message length or part limit");
        }
        ArrayList<EntityRange> snapshot = new ArrayList<>(entities);
        ArrayList<EntityRange> atoms = new ArrayList<>();
        HashSet<Integer> indices = new HashSet<>();
        for (EntityRange entity : snapshot) {
            if (entity == null || entity.end > parsedText.length()
                    || insideSurrogatePair(parsedText, entity.start)
                    || insideSurrogatePair(parsedText, entity.end)) {
                throw new IllegalArgumentException("Entity range is outside text or splits a surrogate pair");
            }
            if (entity.sourceIndex >= 0 && !indices.add(entity.sourceIndex)) {
                throw new IllegalArgumentException("Duplicate entity source index");
            }
            if (entity.atomic) atoms.add(entity);
        }
        atoms.sort(Comparator.comparingInt((EntityRange entity) -> entity.start)
                .thenComparingInt(entity -> entity.end));
        ArrayList<int[]> protectedRanges = new ArrayList<>();
        for (EntityRange atom : atoms) {
            int[] last = protectedRanges.isEmpty() ? null : protectedRanges.get(protectedRanges.size() - 1);
            // Touching atoms may be separated at their common boundary.
            if (last != null && atom.start < last[1]) {
                last[1] = Math.max(last[1], atom.end);
            } else {
                protectedRanges.add(new int[]{atom.start, atom.end});
            }
        }
        for (int[] interval : protectedRanges) {
            if (interval[1] - interval[0] > maxUtf16) {
                throw new IllegalArgumentException("实体或受保护内容超过单条消息长度上限，无法安全拆分。");
            }
        }
        List<SummaryTextSplitter.Range> ranges = SummaryTextSplitter.splitRanges(parsedText, maxUtf16,
                offset -> canBreakAt(protectedRanges, offset));
        if (ranges.size() > maxParts) {
            throw new IllegalArgumentException("总结拆分后超过 " + maxParts + " 条消息，未生成发送计划。");
        }
        ArrayList<Part> parts = new ArrayList<>(ranges.size());
        for (SummaryTextSplitter.Range range : ranges) {
            ArrayList<EntitySlice> slices = new ArrayList<>();
            for (EntityRange entity : snapshot) {
                if (entity.sourceIndex < 0) continue;
                int start = Math.max(range.start, entity.start);
                int end = Math.min(range.end, entity.end);
                if (start >= end) continue;
                if (entity.atomic && (start != entity.start || end != entity.end)) {
                    throw new IllegalStateException("Atomic entity was split");
                }
                slices.add(new EntitySlice(entity.sourceIndex, start - range.start, end - start));
            }
            parts.add(new Part(range.start, range.end, parsedText.substring(range.start, range.end), slices));
        }
        return new SummaryPublishPlan(parsedText, parts, maxUtf16);
    }

    private static boolean insideSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static boolean canBreakAt(List<int[]> intervals, int offset) {
        int low = 0, high = intervals.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int[] interval = intervals.get(middle);
            if (offset <= interval[0]) high = middle - 1;
            else if (offset >= interval[1]) low = middle + 1;
            else return false;
        }
        return true;
    }
}
