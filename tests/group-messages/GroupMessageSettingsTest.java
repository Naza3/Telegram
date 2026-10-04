package org.telegram.messenger.groupmessages;

import android.content.Context.MemoryPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GroupMessageSettingsTest {
    private static int assertions;
    public static void main(String[] args) {
        parsing(); isolationAndDurability();
        System.out.println("GroupMessageSettingsTest: " + assertions + " assertions passed");
    }
    private static void parsing() {
        Set<Long> ids = GroupMessageSettings.parseUids(" 7,9，7\n9223372036854775807\t11 ");
        eq(4, ids.size()); yes(ids.contains(Long.MAX_VALUE)); eq("7\n9\n11\n9223372036854775807", GroupMessageSettings.formatUids(ids));
        fail(() -> ids.add(10L));
        eq(0, GroupMessageSettings.parseUids(" \n，,").size());
        for (String invalid : new String[]{"0", "-7", "@bot", "1.0", "1e9", "+5", "9223372036854775808", "12345678901234567890", "１２３"}) {
            fail(() -> GroupMessageSettings.parseUids(invalid));
        }
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 1000; i++) many.append(i).append('\n');
        eq(1000, GroupMessageSettings.parseUids(many.toString()).size());
        many.append("1001"); fail(() -> GroupMessageSettings.parseUids(many.toString()));
        fail(() -> GroupMessageSettings.parseUids(" ".repeat(24001)));
        List<String> words = GroupMessageSettings.parseKeywords(" Offer \r\noffer\r[ad].*\n  广告  \n\n");
        eq(List.of("Offer", "[ad].*", "广告"), words); fail(() -> words.clear());
        eq(1, GroupMessageSettings.parseKeywords("x".repeat(100)).size());
        fail(() -> GroupMessageSettings.parseKeywords("x".repeat(101)));
        fail(() -> GroupMessageSettings.parseKeywords("a\tb"));
        List<String> maximum = new ArrayList<>();
        for (int i = 0; i < 100; i++) maximum.add("word" + i);
        eq(100, GroupMessageSettings.parseKeywords(String.join("\n", maximum)).size());
        maximum.add("last"); fail(() -> GroupMessageSettings.parseKeywords(String.join("\n", maximum)));
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr"));
            eq(List.of("I"), GroupMessageSettings.parseKeywords("I\ni"));
        } finally { java.util.Locale.setDefault(old); }
    }
    private static void isolationAndDurability() {
        UserConfig.getInstance(0).owner = 100;
        UserConfig.getInstance(1).owner = 200;
        GroupMessageSettings.Rule empty = GroupMessageSettings.get(0, -10);
        eq(0, empty.filterMode); no(empty.antiRevoke); eq(0L, empty.revision);
        GroupMessageSettings.Rule first = GroupMessageSettings.save(0, 100, -10, 0, 1, "55", "ad", true);
        eq(1L, first.revision); yes(first.antiRevoke); eq(Set.of(55L), first.uids);
        eq(1, prefs(0, 100).commits);
        fail(() -> first.keywords.add("new"));
        eq(0, GroupMessageSettings.get(1, -10).filterMode);
        eq(0, GroupMessageSettings.get(0, -11).filterMode);
        fail(() -> GroupMessageSettings.save(0, 100, -10, 0, 2, "66", "", false));
        fail(() -> GroupMessageSettings.save(0, 100, -10, 1, 2, "-1", "", false));
        eq(1, prefs(0, 100).commits);
        eq(1, GroupMessageSettings.get(0, -10).filterMode);
        GroupMessageSettings.Rule disabled = GroupMessageSettings.save(0, 100, -10, 1, 0, "55", "ad", false);
        eq(0, disabled.filterMode); eq(Set.of(55L), disabled.uids); no(disabled.antiRevoke);
        MemoryPreferences oldPrefs = prefs(0, 100);
        String durableBefore = oldPrefs.values.get("-10");
        oldPrefs.failCommit = true;
        fail(() -> GroupMessageSettings.save(0, 100, -10, 2, 2, "66", "test", true));
        eq(durableBefore, oldPrefs.values.get("-10"));
        eq(2L, GroupMessageSettings.get(0, -10).revision);
        oldPrefs.failCommit = false;
        UserConfig.getInstance(0).owner = 101;
        eq(0, GroupMessageSettings.get(0, -10).filterMode);
        fail(() -> GroupMessageSettings.load(0, 100, -10));
        fail(() -> GroupMessageSettings.save(0, 100, -10, 2, 1, "55", "", true));
        GroupMessageSettings.save(0, 101, -10, 0, 2, "77", "new", false);
        yes(GroupMessageSettings.clearOwner(0, 100));
        eq(2, GroupMessageSettings.get(0, -10).filterMode); eq(0, oldPrefs.values.size());
        prefs(0, 101).values.put("-12", "{bad data");
        eq(0, GroupMessageSettings.get(0, -12).filterMode); no(GroupMessageSettings.get(0, -12).antiRevoke);
        fail(() -> GroupMessageSettings.load(0, 101, -12));
        prefs(0, 101).duringCommit = () -> UserConfig.getInstance(0).owner = 102;
        fail(() -> GroupMessageSettings.save(0, 101, -10, 1, 1, "88", "", true));
        eq(0, GroupMessageSettings.get(0, -10).filterMode);
        yes(GroupMessageSettings.clearOwner(0, 101));
        GroupMessageSettings.save(0, 102, -10, 0, 1, "99", "safe", false);
        // Force cache eviction, then decode the real durable value with full 64-bit UIDs.
        GroupMessageSettings.save(0, 102, -20, 0, 2, "9223372036854775807", "AbC\n[.*]", true);
        for (int i = 1000; i < 1600; i++) GroupMessageSettings.get(0, -i);
        GroupMessageSettings.Rule decoded = GroupMessageSettings.load(0, 102, -20);
        eq(Set.of(Long.MAX_VALUE), decoded.uids); eq(List.of("AbC", "[.*]"), decoded.keywords); yes(decoded.antiRevoke);
        fail(() -> GroupMessageSettings.load(0, 102, 5));
        fail(() -> GroupMessageSettings.load(0, 102, Long.MIN_VALUE));
        eq(0, GroupMessageSettings.get(-1, -10).filterMode);
        eq(0, GroupMessageSettings.get(0, 5).filterMode);
        UserConfig.getInstance(0).owner = 0;
        eq(0, GroupMessageSettings.get(0, -20).filterMode);
        no(GroupMessageSettings.get(0, -20).antiRevoke);
    }
    private static MemoryPreferences prefs(int account, long owner) {
        return ApplicationLoader.applicationContext.getSharedPreferences("group_messages_" + account + "_" + owner, 0);
    }
    private static void eq(Object expected, Object actual) {
        assertions++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    private static void yes(boolean value) { assertions++; if (!value) throw new AssertionError("expected true"); }
    private static void no(boolean value) { yes(!value); }
    private static void fail(Runnable action) {
        assertions++; try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("expected failure");
    }
}
