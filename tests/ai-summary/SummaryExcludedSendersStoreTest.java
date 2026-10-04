/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.messenger.UserConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public final class SummaryExcludedSendersStoreTest {
    private static final long OWNER = 1000, GROUP = -900;
    private static int assertions;

    public static void main(String[] args) {
        parseDecimal(); invalidInput(); snapshotsAndRevisions(); isolation(); storageFailures(); corruptStorage(); capacity();
        System.out.println("SummaryExcludedSendersStoreTest: 7 cases passed, " + assertions + " assertions");
    }

    private static void parseDecimal() {
        Set<Long> ids = SummaryExcludedSendersStore.parse(" 0001,2，3\r\n2\t9007199254740993\u00a0" + Long.MAX_VALUE + "\u3000");
        check(ids.equals(new TreeSet<>(Arrays.asList(1L, 2L, 3L, 9007199254740993L, Long.MAX_VALUE))), "UID precision lost or separators/deduplication incorrect");
        check(SummaryExcludedSendersStore.format(ids).equals("1\n2\n3\n9007199254740993\n9223372036854775807"), "normalized list should sort exact decimal values");
        check(SummaryExcludedSendersStore.parse(" \n\r,，\t").isEmpty(), "empty list must support explicit clearing");
        expect(() -> ids.clear());
        Set<Long> max = new HashSet<>();
        for (long i = 1; i <= SummaryExcludedSendersStore.MAX_UIDS; i++) max.add(i);
        check(SummaryExcludedSendersStore.parse(SummaryExcludedSendersStore.format(max)).equals(max), "maximum list did not round-trip");
    }

    private static void invalidInput() {
        for (String input : new String[]{null, "0", "0000", "-1", "+1", "1.0", "1e3", "NaN", "@someone", "123abc", "１２３", "١٢٣", "123\u0000", "123\u200b", "9223372036854775808", "18446744073709551615", "00000000000000000001", "1".repeat(24001)}) {
            expect(() -> SummaryExcludedSendersStore.parse(input));
        }
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= SummaryExcludedSendersStore.MAX_UIDS + 1; i++) many.append(i).append('\n');
        expect(() -> SummaryExcludedSendersStore.parse(many.toString()));
        check(SummaryExcludedSendersStore.parse("1,".repeat(1001)).size() == 1, "duplicate entries should not consume UID capacity");
        expect(() -> SummaryExcludedSendersStore.format(Collections.singleton(0L)));
        expect(() -> SummaryExcludedSendersStore.format(Collections.singleton(null)));
    }

    private static void snapshotsAndRevisions() {
        reset();
        SummaryExcludedSendersStore.Snapshot empty = load(GROUP);
        check(empty.revision == 0 && empty.ids.isEmpty(), "new group inherited exclusions");
        Set<Long> original = new HashSet<>(Arrays.asList(11L, 9007199254740993L));
        SummaryExcludedSendersStore.Snapshot first = save(GROUP, empty.revision, original);
        original.clear();
        check(first.revision == 1 && first.ids.size() == 2, "snapshot retained mutable caller data");
        expect(() -> first.ids.add(99L));
        check(load(GROUP).ids.equals(first.ids), "saved 64-bit UID list changed after disk round-trip");
        expect(() -> save(GROUP, 0, Collections.singleton(22L)));
        check(load(GROUP).ids.equals(first.ids), "stale initial editor overwrote a saved list");
        SummaryExcludedSendersStore.Snapshot cleared = save(GROUP, first.revision, Collections.emptySet());
        check(cleared.revision == 2 && load(GROUP).ids.isEmpty(), "clearing must retain a revision tombstone");
        expect(() -> save(GROUP, first.revision, first.ids));
        expect(() -> save(GROUP, 0, first.ids));
        check(load(GROUP).ids.isEmpty(), "old editor resurrected a cleared exclusion list");
        SummaryExcludedSendersStore.Snapshot replacement = save(GROUP, cleared.revision, Collections.singleton(44L));
        check(replacement.revision == 3 && replacement.ids.equals(Collections.singleton(44L)), "fresh editor could not replace a cleared list");
        for (long dialog : new long[]{0, 1, Long.MIN_VALUE}) expect(() -> load(dialog));
        expect(() -> save(GROUP, -1, Collections.emptySet()));
    }

    private static void isolation() {
        reset();
        save(GROUP, 0, Collections.singleton(12L));
        check(load(GROUP - 1).ids.isEmpty(), "group isolation failed");
        UserConfig.getInstance(1).setClientUserId(OWNER);
        check(SummaryExcludedSendersStore.load(1, OWNER, GROUP).ids.isEmpty(), "account slot isolation failed");
        UserConfig.getInstance(0).setClientUserId(2000);
        expect(() -> load(GROUP));
        expect(() -> save(GROUP, 1, Collections.singleton(13L)));
        check(SummaryExcludedSendersStore.load(0, 2000, GROUP).ids.isEmpty(), "reused account slot inherited exclusions");
        SummaryExcludedSendersStore.save(0, 2000, GROUP, 0, Collections.singleton(88L));
        SummaryPrivateStorage.write("saved_prompts", 0, OWNER, "other namespace", 1000);
        SummaryExcludedSendersStore.clearOwner(0, OWNER);
        check(SummaryExcludedSendersStore.load(0, 2000, GROUP).ids.equals(Collections.singleton(88L)), "old-owner cleanup erased current-owner exclusions");
        check(SummaryPrivateStorage.read("saved_prompts", 0, OWNER, 1000).equals("other namespace"), "UID cleanup erased other private records");
        UserConfig.getInstance(0).setClientUserId(OWNER);
        check(load(GROUP).ids.isEmpty(), "logout cleanup retained old exclusions");
        expect(() -> SummaryExcludedSendersStore.load(-1, OWNER, GROUP));
        expect(() -> SummaryExcludedSendersStore.load(0, 0, GROUP));
    }

    private static void storageFailures() {
        reset();
        SummaryExcludedSendersStore.Snapshot first = save(GROUP, 0, Collections.singleton(9876543210L));
        String location = SummaryPrivateStorage.key("excluded_senders", 0, OWNER);
        String encrypted = SummaryPrivateStorage.FILES.get(location);
        check(encrypted != null && encrypted.startsWith("v1:") && !encrypted.contains("9876543210"), "UID list saved as plaintext");
        SummaryPrivateStorage.failWrites = 1;
        expect(() -> save(GROUP, first.revision, Collections.emptySet()));
        check(load(GROUP).revision == first.revision && load(GROUP).ids.equals(first.ids), "failed save removed existing exclusions");
        check(encrypted.equals(SummaryPrivateStorage.FILES.get(location)), "failed save replaced old ciphertext");
        SummaryPrivateStorage.failReads = 1;
        expect(() -> save(GROUP, first.revision, Collections.singleton(55L)));
        check(load(GROUP).ids.equals(first.ids), "read failure fell back to empty and overwrote list");
        SummaryPrivateStorage.FILES.put(location, encrypted.substring(0, encrypted.length() - 5) + "AAAAA");
        expect(() -> load(GROUP));
        expect(() -> save(GROUP, 0, Collections.emptySet()));
    }

    private static void corruptStorage() {
        String[] bad = {
            "{\"version\":2,\"groups\":[]}",
            "{\"version\":1.0,\"groups\":[]}",
            jsonGroup("-900", "1", "[9007199254740993]"),
            jsonGroup("-900", "1", "[\"1\",\"1\"]"),
            jsonGroup("-900", "1", "[\"-3\"]"),
            jsonGroup("-900", "0", "[]"),
            jsonGroup("-9223372036854775808", "1", "[]"),
            jsonGroup("900", "1", "[]"),
            jsonGroup("-900", "1e3", "[]")
        };
        for (String invalid : bad) {
            reset();
            SummaryPrivateStorage.write("excluded_senders", 0, OWNER, invalid, SummaryExcludedSendersStore.MAX_STORAGE_BYTES);
            expect(() -> load(GROUP));
            expect(() -> save(GROUP, 0, Collections.singleton(7L)));
            check(SummaryPrivateStorage.read("excluded_senders", 0, OWNER, SummaryExcludedSendersStore.MAX_STORAGE_BYTES).equals(invalid), "corrupt exclusions were silently replaced");
        }
        reset();
        SummaryPrivateStorage.write("excluded_senders", 0, OWNER, jsonGroup("-900", Long.toString(Long.MAX_VALUE), "[]"), 1000);
        expect(() -> save(GROUP, Long.MAX_VALUE, Collections.singleton(7L)));
    }

    private static void capacity() {
        reset();
        for (int i = 1; i <= SummaryExcludedSendersStore.MAX_GROUPS; i++) save(-i, 0, Collections.singleton(1L));
        expect(() -> save(-1000, 0, Collections.singleton(1L)));
        check(load(-1).ids.equals(Collections.singleton(1L)), "capacity evicted existing group");
        check(save(-1, 1, Collections.singleton(2L)).ids.equals(Collections.singleton(2L)), "capacity blocked editing existing group");
        Set<Long> oversized = new HashSet<>();
        for (long i = 1; i <= SummaryExcludedSendersStore.MAX_UIDS + 1; i++) oversized.add(i);
        expect(() -> save(-1, 2, oversized));
        check(load(-1).ids.equals(Collections.singleton(2L)), "invalid oversized set overwrote previous list");
        expect(() -> save(-1, 2, Collections.singleton(-1L)));
    }

    private static String jsonGroup(String dialog, String revision, String ids) {
        return "{\"version\":1,\"groups\":[{\"dialog\":\"" + dialog + "\",\"revision\":\"" + revision + "\",\"ids\":" + ids + "}]}";
    }
    private static SummaryExcludedSendersStore.Snapshot load(long dialog) { return SummaryExcludedSendersStore.load(0, OWNER, dialog); }
    private static SummaryExcludedSendersStore.Snapshot save(long dialog, long revision, Set<Long> ids) { return SummaryExcludedSendersStore.save(0, OWNER, dialog, revision, ids); }
    private static void reset() { SummaryPrivateStorage.reset(); UserConfig.getInstance(0).setClientUserId(OWNER); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void expect(Runnable run) {
        assertions++; try { run.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("expected rejection");
    }
}
