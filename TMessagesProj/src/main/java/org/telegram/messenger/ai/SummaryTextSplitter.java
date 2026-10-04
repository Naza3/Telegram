/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lossless UTF-16 ranges for native message sending. Callers must pass a stable
 * CharSequence and use subSequence(start, end) to retain existing text spans.
 * This helper neither trims text nor parses Markdown/Telegram entities.
 *
 * Boundaries conservatively keep Unicode marks, emoji modifiers/tags, ZWJ
 * sequences, regional-indicator pairs, Hangul and virama conjuncts together.
 * Character properties follow the Java/Android runtime's Unicode tables;
 * this is not a claim of conformance to every version of UAX #29.
 */
public final class SummaryTextSplitter {
    private SummaryTextSplitter() { }

    /** Additional restrictions on internal UTF-16 cuts; outer text boundaries are implicit. */
    public interface BoundaryPolicy {
        boolean canBreakAt(int offset);
    }

    public static final class Range {
        public final int start;
        public final int end;

        private Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int length() { return end - start; }
    }

    public static final class GraphemeTooLongException extends IllegalArgumentException {
        public final int start;
        public final int end;
        public final int limit;

        private GraphemeTooLongException(int start, int end, int limit) {
            super("单个字符组合超过单条消息长度上限，无法安全拆分。");
            this.start = start;
            this.end = end;
            this.limit = limit;
        }
    }

    /**
     * The limit is UTF-16 code units, matching String.length and Telegram entity
     * offsets. Successful ranges are nonempty, contiguous, end-exclusive and
     * each at most the limit. Concatenating them reproduces every original unit.
     * Compute the entire result before sending anything: an oversized cluster
     * anywhere in the input throws instead of returning a partially sendable list.
     */
    public static List<Range> splitRanges(CharSequence text, int maxUtf16Length) {
        return splitRanges(text, maxUtf16Length, offset -> true);
    }

    /**
     * Applies the policy only at existing Unicode-cluster boundaries. Preferred
     * paragraph/newline/space cuts must also be permitted; a forbidden preferred
     * cut cannot bypass the policy. No result is returned if any part cannot fit.
     */
    public static List<Range> splitRanges(CharSequence text, int maxUtf16Length, BoundaryPolicy policy) {
        if (text == null) throw new IllegalArgumentException("Text must not be null");
        if (maxUtf16Length <= 0) throw new IllegalArgumentException("Length limit must be positive");
        if (policy == null) throw new IllegalArgumentException("Boundary policy must not be null");
        if (text.length() == 0) return Collections.emptyList();
        ArrayList<Range> ranges = new ArrayList<>();
        final int length = text.length();
        int start = 0;
        while (start < length) {
            int scan = start;
            int paragraph = -1;
            int newline = -1;
            int whitespace = -1;
            int allowedEnd = -1;
            int lineBreaks = 0;
            while (scan < length) {
                int end = clusterEnd(text, scan, length);
                if (end - scan > maxUtf16Length) {
                    throw new GraphemeTooLongException(scan, end, maxUtf16Length);
                }
                if (end - start > maxUtf16Length) break;
                int first = Character.codePointAt(text, scan);
                boolean allowed = end == length || policy.canBreakAt(end);
                if (allowed) allowedEnd = end;
                if (isLineBreak(first)) {
                    lineBreaks++;
                    if (allowed) {
                        newline = end;
                        if (lineBreaks >= 2 || first == 0x2029) paragraph = end;
                    }
                } else if (!Character.isWhitespace(first) && !Character.isSpaceChar(first)) {
                    lineBreaks = 0;
                }
                if (allowed && (Character.isWhitespace(first) || Character.isSpaceChar(first))) whitespace = end;
                scan = end;
            }
            int end;
            if (scan == length) {
                end = length;
            } else if (paragraph > start) {
                end = paragraph;
            } else if (newline > start) {
                end = newline;
            } else if (whitespace > start) {
                end = whitespace;
            } else {
                end = allowedEnd;
            }
            if (end <= start && allowedEnd < 0) {
                throw new IllegalArgumentException("实体或受保护内容超过单条消息长度上限，无法安全拆分。");
            }
            // clusterEnd always advances; the explicit check prevents regressions
            // in boundary handling from becoming a send loop with empty messages.
            if (end <= start) throw new IllegalStateException("Text splitting made no progress");
            ranges.add(new Range(start, end));
            start = end;
        }
        return Collections.unmodifiableList(ranges);
    }

    private static int clusterEnd(CharSequence text, int start, int length) {
        int previous = Character.codePointAt(text, start);
        int end = start + Character.charCount(previous);
        int regionalCount = isRegional(previous) ? 1 : 0;
        boolean pendingZwj = previous == 0x200d;
        boolean pendingVirama = isVirama(previous);
        boolean pendingPrepend = isPrepend(previous);
        while (end < length) {
            int next = Character.codePointAt(text, end);
            boolean extend = isExtend(next);
            boolean join;
            if (previous == '\r' && next == '\n') {
                join = true;
            } else if (isControl(previous) || isControl(next)) {
                join = false;
            } else {
                join = extend || next == 0x200d || pendingZwj || pendingPrepend
                        || (pendingVirama && Character.isLetter(next))
                        || joinsHangul(previous, next)
                        || (isRegional(next) && regionalCount % 2 == 1);
            }
            if (!join) break;
            end += Character.charCount(next);
            if (next == 0x200d) {
                pendingZwj = true;
            } else if (!extend) {
                pendingZwj = false;
            }
            if (isVirama(next)) pendingVirama = true;
            else if (!extend && next != 0x200d) pendingVirama = false;
            if (isPrepend(next)) pendingPrepend = true;
            else if (!extend && next != 0x200d) pendingPrepend = false;
            if (isRegional(next)) regionalCount++;
            else if (!extend && next != 0x200d) regionalCount = 0;
            previous = next;
        }
        return end;
    }

    private static boolean isExtend(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK || cp == 0x200c
                || (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef)
                || (cp >= 0x1f3fb && cp <= 0x1f3ff) || (cp >= 0xe0020 && cp <= 0xe007f);
    }

    private static boolean isControl(int cp) {
        if (cp == 0x200d || isExtend(cp) || isPrepend(cp)) return false;
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean isLineBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    private static boolean isRegional(int cp) { return cp >= 0x1f1e6 && cp <= 0x1f1ff; }

    private static boolean isPrepend(int cp) {
        return (cp >= 0x600 && cp <= 0x605) || cp == 0x6dd || cp == 0x70f
                || cp == 0x890 || cp == 0x891 || cp == 0x8e2 || cp == 0xd4e
                || cp == 0x110bd || cp == 0x110cd || cp == 0x111c2 || cp == 0x111c3
                || cp == 0x1193f || cp == 0x11941 || cp == 0x11a3a
                || (cp >= 0x11a84 && cp <= 0x11a89) || cp == 0x11d46 || cp == 0x11f02;
    }

    private static boolean isVirama(int cp) {
        if (!isExtend(cp)) return false;
        // Available since API 19. Covers viramas/halants recognized by that
        // runtime without copying a second full Unicode character database.
        String name = Character.getName(cp);
        return name != null && (name.contains("VIRAMA") || name.contains("HALANT"));
    }

    private static boolean joinsHangul(int before, int after) {
        int a = hangulType(before), b = hangulType(after);
        return a == 1 && (b == 1 || b == 2 || b == 4 || b == 5)
                || (a == 2 || a == 4) && (b == 2 || b == 3)
                || (a == 3 || a == 5) && b == 3;
    }

    private static int hangulType(int cp) {
        if (cp >= 0x1100 && cp <= 0x115f || cp >= 0xa960 && cp <= 0xa97c) return 1;
        if (cp >= 0x1160 && cp <= 0x11a7 || cp >= 0xd7b0 && cp <= 0xd7c6) return 2;
        if (cp >= 0x11a8 && cp <= 0x11ff || cp >= 0xd7cb && cp <= 0xd7fb) return 3;
        if (cp >= 0xac00 && cp <= 0xd7a3) return (cp - 0xac00) % 28 == 0 ? 4 : 5;
        return 0;
    }
}
