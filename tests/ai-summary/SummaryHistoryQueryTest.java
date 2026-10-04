/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SummaryHistoryQueryTest {
    private static int assertions;
    public static void main(String[] args) {
        SummaryHistoryStore.Record a = record("a", -100, 0, "研发 ＡＩ", "关于 Qwen 的版本讨论 🧭");
        SummaryHistoryStore.Record b = record("b", -100, 42, "研发群", "上线结论尚未确定");
        SummaryHistoryStore.Record c = record("c", -200, 42, "其他群", "同样包含 QWEN");
        List<SummaryHistoryStore.Record> source = Arrays.asList(a, b, c);
        check(ids(SummaryHistoryQuery.filter(source, 0, -1, "qWeN")).equals("ac"), "case-insensitive body search failed");
        check(ids(SummaryHistoryQuery.filter(source, 0, -1, " ai ")).equals("a"), "fullwidth group-name search failed");
        check(ids(SummaryHistoryQuery.filter(source, -100, -1, "")).equals("ab"), "group filter lost topic records");
        check(ids(SummaryHistoryQuery.filter(source, -100, 0, "")).equals("a"), "topic0 became all-topics");
        check(ids(SummaryHistoryQuery.filter(source, -100, 42, "")).equals("b"), "same topicID leaked different group");
        check(ids(SummaryHistoryQuery.filter(source, -100, -1, "🧭")).equals("a"), "Unicode search failed");
        check(SummaryHistoryQuery.filter(source, 0, -1, "不存在").isEmpty(), "unmatched query leaked record");
        check(source.size() == 3, "search mutated stored snapshot");
        List<SummaryHistoryStore.Record> result = SummaryHistoryQuery.filter(source, 0, -1, "");
        boolean immutable = false; try { result.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "search result mutable");
        System.out.println("SummaryHistoryQueryTest: " + assertions + " assertions passed");
    }
    private static SummaryHistoryStore.Record record(String id, long dialog, long topic, String title, String body) {
        return new SummaryHistoryStore.Record(id, dialog, topic, 1000, title, "最近10条", "", "", "我的要求", "", body,
                false, Collections.emptyList(), false);
    }
    private static String ids(List<SummaryHistoryStore.Record> list) { StringBuilder s = new StringBuilder(); for (SummaryHistoryStore.Record r : list) s.append(r.id); return s.toString(); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
