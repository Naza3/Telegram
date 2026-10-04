package org.telegram.messenger.ai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class SummaryTextSplitterTest {
    private static int assertions;

    public static void main(String[] args) {
        chineseHardBoundary();
        emojiAndMarks();
        separatorsArePreserved();
        invalidLimitsAndOversizedClusters();
        charSequenceRangesAndEntityOffsets();
        deterministicMixedText();
        System.out.println("SummaryTextSplitterTest: " + assertions + " assertions passed");
    }

    private static void chineseHardBoundary() {
        String text = repeat("中", 4096) + "尾";
        List<SummaryTextSplitter.Range> ranges = verify(text, 4096);
        check(ranges.size() == 2 && ranges.get(0).end == 4096, "Chinese hard split moved unexpectedly");
        check(ranges.get(1).start == 4096 && text.substring(ranges.get(1).start).equals("尾"),
                "max+1 Chinese character was skipped by end+1");
        verify(repeat("汉字", 10000), 127);
        check(verify("甲乙丙", 1).size() == 3, "limit-one must advance exactly one Chinese character");
        check(verify("甲乙丙", Integer.MAX_VALUE).size() == 1, "large limit overflowed");
        check(verify("  甲\n乙  ", 100).size() == 1, "fitting text was needlessly split or trimmed");
    }

    private static void emojiAndMarks() {
        List<String> clusters = Arrays.asList(
                "\ud83d\ude00",                            // Supplementary scalar.
                "\ud83d\udc4d\ud83c\udffd",              // Skin-tone modifier.
                "\ud83d\udc69\u200d\ud83d\udcbb",       // Woman technologist.
                "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66",
                "\u2764\ufe0f", "1\ufe0f\u20e3",       // Variation selector and keycap.
                "a\u0301\u0327",                         // Multiple combining marks.
                "\u0915\u093f",                          // Spacing combining vowel.
                "\u0915\u094d\u0937",                   // Virama conjunct.
                "\u1100\u1161\u11a8",                   // Decomposed Hangul syllable.
                "\ud83c\udde8\ud83c\uddf3",             // Regional-indicator flag.
                "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc65\udb40\udc6e\udb40\udc67\udb40\udc7f",
                "文\udb40\udd00");                      // Supplementary variation selector.
        for (String cluster : clusters) {
            String text = "x" + cluster + "y";
            List<SummaryTextSplitter.Range> ranges = verify(text, cluster.length());
            Set<Integer> allowed = new HashSet<>(Arrays.asList(0, 1, 1 + cluster.length(), text.length()));
            for (SummaryTextSplitter.Range range : ranges) {
                check(allowed.contains(range.start) && allowed.contains(range.end), "split inside Unicode cluster");
            }
            try {
                SummaryTextSplitter.splitRanges(text, cluster.length() - 1);
                throw new AssertionError("oversized Unicode cluster accepted");
            } catch (SummaryTextSplitter.GraphemeTooLongException expected) {
                check(expected.start == 1 && expected.end == 1 + cluster.length(), "wrong oversized-cluster range");
            }
        }
        String flags = "\ud83c\udde8\ud83c\uddf3\ud83c\uddfa\ud83c\uddf8\ud83c\uddef\ud83c\uddf5";
        List<SummaryTextSplitter.Range> flagRanges = verify(flags, 5);
        check(flagRanges.size() == 3, "regional indicators were not paired as flags");
        for (SummaryTextSplitter.Range range : flagRanges) check(range.length() == 4, "split inside flag pair");
        verify("A\u200d\ufe0fB中", 4); // Conservative ZWJ chain includes intervening selectors.
        verify("\u0600\u0301中尾", 3); // Prepend remains attached across an extending mark.
    }

    private static void separatorsArePreserved() {
        String paragraphs = "abcd\n\nxy z更多文字";
        List<SummaryTextSplitter.Range> ranges = verify(paragraphs, 10);
        check(ranges.get(0).end == 6, "paragraph boundary should take precedence over later whitespace");
        String lines = "abcd\nxy z更多文字";
        check(verify(lines, 9).get(0).end == 5, "newline boundary should take precedence over a later space");
        check(verify("abcd xy更多文字", 8).get(0).end == 5, "space must stay in preceding segment");
        String crlf = "甲\r\n\r\n乙\t \n\n丙\u2028丁\u2029尾";
        verify(crlf, 4);
        for (SummaryTextSplitter.Range range : verify(crlf, 4)) {
            check(!(range.end < crlf.length() && crlf.charAt(range.end - 1) == '\r'
                    && crlf.charAt(range.end) == '\n'), "CRLF split across messages");
        }
        verify(repeat(" \t\r\n\n", 1000), 63);
        verify("\n\n     \n\t", 2);
        verify(" leading trailing ", 5);
        // Existing unpaired UTF-16 units are preserved verbatim; the splitter
        // introduces no new cut inside any valid surrogate pair.
        verify("A\ud800B\udc00C", 2);
    }

    private static void invalidLimitsAndOversizedClusters() {
        check(SummaryTextSplitter.splitRanges("", 1).isEmpty(), "empty input must not send an empty part");
        for (int limit : new int[]{0, -1, Integer.MIN_VALUE}) {
            try { SummaryTextSplitter.splitRanges("x", limit); throw new AssertionError("invalid limit accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        try { SummaryTextSplitter.splitRanges(null, 5); throw new AssertionError("null accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        String tooLong = repeat("字", 20) + "a" + repeat("\u0301", 50);
        try {
            SummaryTextSplitter.splitRanges(tooLong, 10);
            throw new AssertionError("later oversized cluster returned a partially sendable result");
        } catch (SummaryTextSplitter.GraphemeTooLongException expected) {
            check(expected.start == 20 && expected.end == tooLong.length() && expected.limit == 10,
                    "late oversized cluster was not reported precisely");
        }
        try {
            SummaryTextSplitter.splitRanges("a\r\nb", 1);
            throw new AssertionError("CRLF was split to fit an impossible limit");
        } catch (SummaryTextSplitter.GraphemeTooLongException expected) { assertions++; }
        try { SummaryTextSplitter.splitRanges("x", 1).add(null); throw new AssertionError("mutable range list"); }
        catch (UnsupportedOperationException expected) { assertions++; }
    }

    private static void charSequenceRangesAndEntityOffsets() {
        final String text = "粗体中文连续文字\ud83d\ude00结束";
        CharSequence sequence = new CharSequence() {
            @Override public int length() { return text.length(); }
            @Override public char charAt(int index) { return text.charAt(index); }
            @Override public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
            @Override public String toString() { throw new AssertionError("splitter must not coerce a spanned input to String"); }
        };
        List<SummaryTextSplitter.Range> ranges = SummaryTextSplitter.splitRanges(sequence, 5);
        StringBuilder joined = new StringBuilder();
        for (SummaryTextSplitter.Range range : ranges) joined.append(sequence.subSequence(range.start, range.end));
        check(joined.toString().equals(text), "CharSequence range mapping changed content");
        // Demonstrates safe UTF-16 intersection for a splittable style entity.
        // Atomic URL/mention/custom-emoji entities require caller-side handling;
        // the helper deliberately does not invent or parse Telegram entities.
        int entityStart = 2, entityEnd = 10, covered = 0;
        for (SummaryTextSplitter.Range range : ranges) {
            int begin = Math.max(entityStart, range.start), end = Math.min(entityEnd, range.end);
            if (begin < end) {
                int localOffset = begin - range.start;
                check(localOffset >= 0 && localOffset + end - begin <= range.length(), "entity intersection escaped part");
                covered += end - begin;
            }
        }
        check(covered == entityEnd - entityStart, "formatting range lost UTF-16 coverage");
    }

    private static void deterministicMixedText() {
        String[] pieces = {"中", "文", "x", " ", "\n", "\r\n", "a\u0301", "\u2764\ufe0f",
                "\ud83d\udc69\u200d\ud83d\udcbb", "\ud83c\udde8\ud83c\uddf3", "\ud83d\udc4d\ud83c\udffd"};
        Random random = new Random(12012);
        for (int run = 0; run < 80; run++) {
            StringBuilder text = new StringBuilder();
            Set<Integer> boundaries = new HashSet<>(); boundaries.add(0);
            for (int i = 0; i < 100; i++) {
                text.append(pieces[random.nextInt(pieces.length)]);
                boundaries.add(text.length());
            }
            for (SummaryTextSplitter.Range range : verify(text.toString(), 7 + random.nextInt(40))) {
                check(boundaries.contains(range.start) && boundaries.contains(range.end),
                        "mixed-text range split a known grapheme");
            }
        }
    }

    private static List<SummaryTextSplitter.Range> verify(String text, int limit) {
        List<SummaryTextSplitter.Range> ranges = SummaryTextSplitter.splitRanges(text, limit);
        StringBuilder result = new StringBuilder(); int position = 0;
        for (SummaryTextSplitter.Range range : ranges) {
            check(range.start == position && range.end > range.start && range.length() <= limit,
                    "non-contiguous, empty or over-limit range");
            if (range.end < text.length()) {
                check(!(Character.isHighSurrogate(text.charAt(range.end - 1))
                        && Character.isLowSurrogate(text.charAt(range.end))), "surrogate pair was cut");
            }
            result.append(text, range.start, range.end); position = range.end;
        }
        check(position == text.length() && result.toString().equals(text), "split discarded or duplicated text");
        return ranges;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
