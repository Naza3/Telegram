/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SummaryPublishPlanTest {
    private static int assertions;

    public static void main(String[] args) {
        stylesAcrossParts();
        overlappingAndTouchingAtoms();
        protectedWhitespaceBoundaries();
        protectionWithoutAnEntity();
        completePlanLimits();
        unicodeBoundaries();
        invalidRanges();
        immutableSnapshots();
        boundaryPolicyCompatibility();
        variedIntervals();
        System.out.println("SummaryPublishPlanTest: " + assertions + " assertions passed");
    }

    private static void stylesAcrossParts() {
        List<SummaryPublishPlan.EntityRange> entities = Arrays.asList(
                entity(7, 1, 8, false), entity(3, 2, 6, false));
        SummaryPublishPlan plan = verify("abcdefghi", entities, 4, 64);
        check(plan.parts.size() == 3, "style should span three text parts without changing boundaries");
        slice(plan.parts.get(0), 0, 7, 1, 3);
        slice(plan.parts.get(0), 1, 3, 2, 2);
        slice(plan.parts.get(1), 0, 7, 0, 4);
        slice(plan.parts.get(1), 1, 3, 0, 2);
        check(plan.parts.get(2).entities.isEmpty(), "an entity ending at a cut leaked into the next part");
    }

    private static void overlappingAndTouchingAtoms() {
        List<SummaryPublishPlan.EntityRange> entities = Arrays.asList(
                entity(1, 5, 9, true), entity(2, 0, 12, false), entity(0, 2, 6, true));
        SummaryPublishPlan plan = verify("0123456789AB", entities, 7, 64);
        check(plan.parts.size() == 3 && plan.parts.get(0).end == 2
                && plan.parts.get(1).start == 2 && plan.parts.get(1).end == 9,
                "overlapping atoms did not stay in one part");
        slice(plan.parts.get(1), 0, 1, 3, 4);
        slice(plan.parts.get(1), 1, 2, 0, 7);
        slice(plan.parts.get(1), 2, 0, 0, 4);
        fails(() -> SummaryPublishPlan.create("0123456789AB", entities, 6, 64),
                "overlapping atoms larger than the limit must fail even when each atom fits");
        SummaryPublishPlan touching = verify("abcdefgh", Arrays.asList(
                entity(0, 0, 4, true), entity(1, 4, 8, true)), 4, 64);
        check(touching.parts.size() == 2 && touching.parts.get(0).end == 4,
                "touching atoms were incorrectly merged");
        verify("abcdef", Arrays.asList(entity(0, 0, 6, true), entity(1, 1, 5, true),
                entity(2, 0, 6, true)), 6, 64);
    }

    private static void protectedWhitespaceBoundaries() {
        String text = "ab\n\n cd efghij";
        SummaryPublishPlan plan = verify(text, Collections.singletonList(entity(0, 0, 8, true)), 8, 64);
        check(plan.parts.get(0).end == 8,
                "paragraph/newline/space preference cut inside a protected atom");
        SummaryPublishPlan newline = verify("ab\n\nxyz", Collections.emptyList(), 6, 64);
        check(newline.parts.get(0).end == 4, "unprotected paragraph preference changed");
        SummaryPublishPlan spaces = verify("  abc def  ", Collections.singletonList(entity(0, 2, 9, true)), 7, 64);
        check(spaces.parts.get(0).text.equals("  ") && spaces.parts.get(1).text.equals("abc def")
                && spaces.parts.get(2).text.equals("  "), "plan trimmed boundary whitespace");
    }

    private static void protectionWithoutAnEntity() {
        List<SummaryPublishPlan.EntityRange> protections = Arrays.asList(
                entity(-1, 2, 5, true), entity(-1, 4, 7, true));
        SummaryPublishPlan plan = verify("abcdefghij", protections, 5, 64);
        check(plan.parts.get(0).end == 2 && plan.parts.get(1).text.equals("cdefg"),
                "URL-style protection did not control boundaries");
        for (SummaryPublishPlan.Part part : plan.parts) {
            check(part.entities.isEmpty(), "protection-only interval became an outgoing entity");
        }
    }

    private static void completePlanLimits() {
        SummaryPublishPlan plan = verify(repeat("中", 64), Collections.emptyList(), 1, 64);
        check(plan.parts.size() == 64, "exactly 64 parts must be allowed");
        fails(() -> SummaryPublishPlan.create(repeat("中", 65), Collections.emptyList(), 1, 64),
                "65 parts returned a truncated send plan");
        fails(() -> SummaryPublishPlan.create("abc", Collections.emptyList(), 1, 2),
                "caller-specific part limit was ignored");
        fails(() -> SummaryPublishPlan.create("abc" + repeat("d", 20),
                Collections.singletonList(entity(0, 3, 23, true)), 5, 64),
                "a later oversized atom returned a partially sendable plan");
        check(verify("", Collections.emptyList(), 1, 1).parts.isEmpty(), "empty text created a message");
        check(verify("all fits", Collections.emptyList(), Integer.MAX_VALUE, 1).parts.size() == 1,
                "large length limit overflowed");
    }

    private static void unicodeBoundaries() {
        String text = "甲\ud83d\ude00乙a\u0301尾";
        SummaryPublishPlan plan = verify(text, Arrays.asList(entity(0, 1, 3, true),
                entity(1, 0, text.length(), false)), 3, 64);
        check(plan.parts.get(0).text.equals("甲\ud83d\ude00"), "UTF-16 length was treated as code points");
        slice(plan.parts.get(0), 0, 0, 1, 2);
        check(plan.parts.get(1).text.equals("乙a\u0301"), "combining sequence was split");
        String cluster = "\ud83d\udc69\u200d\ud83d\udcbb";
        verify("x" + cluster + "y", Collections.singletonList(entity(0, 1, 1 + cluster.length(), true)),
                cluster.length(), 64);
        // Both atoms fit alone, but their common cut lies inside one Unicode cluster.
        fails(() -> SummaryPublishPlan.create("a\u0301b", Arrays.asList(
                entity(0, 0, 1, true), entity(1, 1, 3, true)), 2, 64),
                "a policy-approved cut split a grapheme");
        fails(() -> SummaryPublishPlan.create("aaa" + cluster, Collections.emptyList(), 4, 64),
                "later oversized grapheme returned a partial plan");
        verify("A\ud800B\udc00C", Collections.emptyList(), 2, 64);
    }

    private static void invalidRanges() {
        fails(() -> SummaryPublishPlan.create(null, Collections.emptyList(), 5, 64), "null text accepted");
        fails(() -> SummaryPublishPlan.create("text", null, 5, 64), "null range list accepted");
        for (int limit : new int[]{0, -1, Integer.MIN_VALUE}) {
            fails(() -> SummaryPublishPlan.create("text", Collections.emptyList(), limit, 64), "bad text limit accepted");
        }
        for (int limit : new int[]{0, -1, 65, Integer.MAX_VALUE}) {
            fails(() -> SummaryPublishPlan.create("text", Collections.emptyList(), 5, limit), "bad part limit accepted");
        }
        fails(() -> entity(-2, 0, 1, true), "invalid negative source index accepted");
        fails(() -> entity(0, -1, 1, true), "negative start accepted");
        fails(() -> entity(0, 0, 0, true), "zero length accepted");
        fails(() -> entity(0, 2, 1, true), "reversed range accepted");
        fails(() -> entity(0, Integer.MAX_VALUE, Integer.MIN_VALUE, true), "overflowed end accepted");
        fails(() -> entity(-1, 0, 1, false), "non-atomic protection accepted");
        fails(() -> SummaryPublishPlan.create("x", Arrays.asList((SummaryPublishPlan.EntityRange) null), 5, 64),
                "null entity accepted");
        fails(() -> SummaryPublishPlan.create("x", Collections.singletonList(entity(0, 0, Integer.MAX_VALUE, false)), 5, 64),
                "out-of-range entity end accepted");
        fails(() -> SummaryPublishPlan.create("abc", Arrays.asList(entity(0, 0, 1, false), entity(0, 1, 2, false)), 5, 64),
                "duplicate native entity identity accepted");
        fails(() -> SummaryPublishPlan.create("\ud83d\ude00", Collections.singletonList(entity(0, 0, 1, false)), 5, 64),
                "entity endpoint inside surrogate pair accepted");
        fails(() -> SummaryPublishPlan.create("\ud83d\ude00", Collections.singletonList(entity(0, 1, 2, false)), 5, 64),
                "entity start inside surrogate pair accepted");
        fails(() -> SummaryPublishPlan.create("", Collections.singletonList(entity(0, 0, 1, true)), 5, 64),
                "empty text accepted a nonempty entity");
    }

    private static void immutableSnapshots() {
        ArrayList<SummaryPublishPlan.EntityRange> input = new ArrayList<>();
        SummaryPublishPlan.EntityRange original = entity(4, 0, 6, false);
        input.add(original);
        SummaryPublishPlan plan = verify("abcdef", input, 3, 64);
        input.clear();
        check(plan.parts.get(0).entities.size() == 1 && plan.parts.get(1).entities.size() == 1,
                "plan retained the caller's mutable range list");
        check(original.start == 0 && original.end == 6, "planning mutated original entity coordinates");
        immutable(() -> plan.parts.clear(), "mutable part list");
        immutable(() -> plan.parts.get(0).entities.clear(), "mutable slice list");
    }

    private static void boundaryPolicyCompatibility() {
        String text = "abc\n\n12 3456789";
        List<SummaryTextSplitter.Range> old = SummaryTextSplitter.splitRanges(text, 9);
        List<SummaryTextSplitter.Range> allowed = SummaryTextSplitter.splitRanges(text, 9, offset -> true);
        check(old.size() == allowed.size(), "allow-all policy changed old range count");
        for (int i = 0; i < old.size(); i++) {
            check(old.get(i).start == allowed.get(i).start && old.get(i).end == allowed.get(i).end,
                    "allow-all policy changed preferred cuts");
        }
        List<SummaryTextSplitter.Range> policy = SummaryTextSplitter.splitRanges("ab\n\n cd efghij", 8, offset -> offset >= 8);
        check(policy.get(0).end == 8, "policy was bypassed by whitespace preference");
        fails(() -> SummaryTextSplitter.splitRanges("abcde", 2, offset -> false), "no permitted boundary accepted");
        check(SummaryTextSplitter.splitRanges("abc", 3, offset -> false).size() == 1,
                "policy incorrectly forbade the implicit outer boundary");
        fails(() -> SummaryTextSplitter.splitRanges("abc", 3, null), "null policy accepted");
    }

    private static void variedIntervals() {
        String text = repeat("甲乙 丙丁\n", 20);
        for (int atomStart = 0; atomStart < 50; atomStart++) {
            List<SummaryPublishPlan.EntityRange> entities = Arrays.asList(
                    entity(0, atomStart, atomStart + 7, true), entity(1, 0, text.length(), false),
                    entity(2, atomStart + 1, atomStart + 6, false));
            verify(text, entities, 7 + atomStart % 8, 64);
        }
    }

    private static SummaryPublishPlan verify(String text, List<SummaryPublishPlan.EntityRange> entities,
            int limit, int maxParts) {
        SummaryPublishPlan plan = SummaryPublishPlan.create(text, entities, limit, maxParts);
        check(plan.normalizedText.equals(text) && plan.maxLength == limit, "plan changed text or length limit");
        check(plan.parts.size() <= maxParts, "plan exceeded caller's part limit");
        StringBuilder restored = new StringBuilder();
        int position = 0;
        for (SummaryPublishPlan.Part part : plan.parts) {
            check(part.start == position && part.end > part.start && part.text.length() <= limit
                    && part.text.equals(text.substring(part.start, part.end)), "invalid text range");
            check(part.end == text.length() || !(Character.isHighSurrogate(text.charAt(part.end - 1))
                    && Character.isLowSurrogate(text.charAt(part.end))), "split a surrogate pair");
            for (SummaryPublishPlan.EntitySlice slice : part.entities) {
                check(slice.sourceIndex >= 0 && slice.offset >= 0 && slice.length > 0
                        && (long) slice.offset + slice.length <= part.text.length(), "invalid relative entity range");
            }
            restored.append(part.text);
            position = part.end;
        }
        check(position == text.length() && restored.toString().equals(text), "plan lost or duplicated text");
        for (SummaryPublishPlan.EntityRange entity : entities) {
            int coverage = 0, count = 0;
            for (SummaryPublishPlan.Part part : plan.parts) {
                if (entity.atomic) check(!(entity.start < part.end && part.end < entity.end), "atomic interval was cut");
                for (SummaryPublishPlan.EntitySlice slice : part.entities) {
                    if (slice.sourceIndex != entity.sourceIndex) continue;
                    int begin = part.start + slice.offset;
                    check(begin >= entity.start && begin + slice.length <= entity.end, "slice escaped original entity");
                    coverage += slice.length;
                    count++;
                }
            }
            if (entity.sourceIndex >= 0) {
                check(coverage == entity.end - entity.start, "entity coverage was lost or duplicated");
                if (entity.atomic) check(count == 1, "atomic entity was duplicated or omitted");
            } else check(coverage == 0 && count == 0, "protection generated an entity");
        }
        return plan;
    }

    private static SummaryPublishPlan.EntityRange entity(int sourceIndex, int start, int end, boolean atomic) {
        return new SummaryPublishPlan.EntityRange(sourceIndex, start, end, atomic);
    }

    private static void slice(SummaryPublishPlan.Part part, int index, int source, int offset, int length) {
        SummaryPublishPlan.EntitySlice slice = part.entities.get(index);
        check(slice.sourceIndex == source && slice.offset == offset && slice.length == length,
                "wrong source identity or relative entity coordinates");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void fails(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }

    private static void immutable(Runnable action, String reason) {
        try { action.run(); } catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }

    private static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }
}
