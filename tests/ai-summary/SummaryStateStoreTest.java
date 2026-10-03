package org.telegram.messenger.ai;

import android.content.MemoryPreferences;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

public final class SummaryStateStoreTest {
    private static int assertions;
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000;
    private static final long CHAT = -123;

    public static void main(String[] args) {
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        MemoryPreferences prefs = MessagesController.getMainSettings(ACCOUNT);
        prefs.values.clear();
        check(state(0).cursor == 0, "no implicit initial cursor");
        SummaryStateStore.CompletionToken first = new SummaryStateStore.CompletionToken();
        check(save(0, 100, 150, true, true, first), "explicit initial batch commits");
        check(state(0).cursor == 150 && state(0).lastLowerExclusive == 100, "initial range");
        check(state(0).resultDigest.length() == 64, "result digest persisted");
        check(!prefs.values.toString().contains("source-private-text"), "no summary plaintext persisted");
        expect(() -> save(150, 150, 175, true, true, first), "one completion commits once");

        save(150, 190, 200, true, false, new SummaryStateStore.CompletionToken());
        check(state(0).cursor == 150 && state(0).lastUpperInclusive == 200, "recent range cannot skip backlog");
        expect(() -> save(150, 190, 200, true, true, new SummaryStateStore.CompletionToken()), "gap rejected");
        check(state(0).cursor == 150, "gap leaves cursor intact");
        save(150, 150, 175, true, true, new SummaryStateStore.CompletionToken());
        check(state(0).cursor == 175, "next continuous batch commits");

        SummaryStateStore.CompletionToken cancelled = new SummaryStateStore.CompletionToken();
        cancelled.cancel();
        check(!save(175, 175, 200, true, true, cancelled), "cancel suppresses publication");
        expect(() -> save(175, 175, 200, false, true, new SummaryStateStore.CompletionToken()), "partial cannot publish");
        expect(() -> save(150, 150, 200, true, true, new SummaryStateStore.CompletionToken()), "stale completion cannot publish");
        check(state(0).cursor == 175, "failed operations preserve cursor");

        prefs.failNextCommits = 1;
        expect(() -> save(175, 175, 200, true, true, new SummaryStateStore.CompletionToken()), "disk error surfaced");
        check(state(0).cursor == 175, "disk failure restores in-memory cursor too");
        check(SummaryStateStore.recordSuccess(ACCOUNT, OWNER, CHAT, 0, 175, 175, 200,
                true, true, 0, "此批没有符合条件的文字消息", "general:1", new SummaryStateStore.CompletionToken()),
                "a fully scanned empty-text batch can advance without an AI call");
        check(state(0).cursor == 200 && state(0).messageCount == 0, "empty batch range recorded");

        check(state(7).cursor == 0, "topics isolated");
        check(SummaryStateStore.load(ACCOUNT, OWNER, -456, 0).cursor == 0, "dialogs isolated");
        UserConfig.getInstance(ACCOUNT).setClientUserId(2000);
        expect(() -> state(0), "old owner cannot read");
        check(SummaryStateStore.load(ACCOUNT, 2000, CHAT, 0).cursor == 0, "slot reuse has no inherited cursor");
        SummaryStateStore.clearOwner(ACCOUNT, OWNER);
        check(prefs.values.isEmpty(), "logout cleans the old owner even after identity changed");
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);

        save(0, 0, 10, true, true, new SummaryStateStore.CompletionToken());
        String key = prefs.values.keySet().iterator().next();
        prefs.values.put(key, "broken");
        expect(() -> state(0), "corrupt state cannot silently reset cursor");
        SummaryStateStore.clear(ACCOUNT, OWNER, CHAT, 0);
        check(state(0).cursor == 0, "explicit reset recovers corrupt state");
        System.out.println("SummaryStateStoreTest: " + assertions + " assertions passed");
    }

    private static SummaryStateStore.State state(long topic) {
        return SummaryStateStore.load(ACCOUNT, OWNER, CHAT, topic);
    }

    private static boolean save(int expected, int lower, int upper, boolean complete, boolean advance,
            SummaryStateStore.CompletionToken token) {
        return SummaryStateStore.recordSuccess(ACCOUNT, OWNER, CHAT, 0, expected, lower, upper,
                complete, advance, 3, "source-private-text", "general:1", token);
    }

    private static void expect(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) {
            assertions++; return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
}
