package org.telegram.messenger.ai;

import android.content.MemoryPreferences;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

public final class SummaryTaskCheckpointTest {
    private static int assertions;
    public static void main(String[] args) {
        UserConfig.getInstance(0).setClientUserId(1000);
        MemoryPreferences prefs = MessagesController.getMainSettings(0);
        prefs.values.clear();
        check(SummaryTaskCheckpoint.read(0, 1000) == null, "no phantom interrupted job");
        check(SummaryTaskCheckpoint.begin(0, 1000, "first", -42, 7, 100), "begin persisted");
        SummaryTaskCheckpoint.Record first = SummaryTaskCheckpoint.read(0, 1000);
        check(first.taskId.equals("first") && first.dialogId == -42 && first.topicId == 7
                && first.startedAtMillis == 100, "read contains only scope and timestamp");
        check(SummaryTaskCheckpoint.begin(0, 1000, "second", -43, 8, 200), "new task replaces marker");
        SummaryTaskCheckpoint.finish(0, 1000, "first");
        check(SummaryTaskCheckpoint.read(0, 1000).taskId.equals("second"), "late old completion cannot clear current task");
        prefs.failNextCommits = 1;
        check(!SummaryTaskCheckpoint.begin(0, 1000, "failed", -45, 0, 300), "disk error is observable");
        check(SummaryTaskCheckpoint.read(0, 1000).taskId.equals("second"), "failed write restores process view");
        prefs.failNextCommits = 1;
        check(!SummaryTaskCheckpoint.finish(0, 1000, "second"), "failed finish does not claim completion");
        check(SummaryTaskCheckpoint.read(0, 1000) != null, "failed finish retains marker");
        check(SummaryTaskCheckpoint.finish(0, 1000, "second") && SummaryTaskCheckpoint.read(0, 1000) == null,
                "successful end clears marker");
        rejects(() -> SummaryTaskCheckpoint.begin(0, 1000, "secret\ntext", -42, 0, 1), "task IDs cannot carry arbitrary text");
        rejects(() -> SummaryTaskCheckpoint.begin(0, 1000, "bad", 42, 0, 1), "private dialogs excluded");
        rejects(() -> SummaryTaskCheckpoint.begin(0, 1000, "bad", -42, -1, 1), "invalid topic excluded");
        SummaryTaskCheckpoint.begin(0, 1000, "owned", -42, 0, 1);
        UserConfig.getInstance(0).setClientUserId(2000);
        rejects(() -> SummaryTaskCheckpoint.read(0, 1000), "old owner cannot read reused account");
        check(SummaryTaskCheckpoint.read(0, 2000) == null, "new owner inherits no task");
        SummaryTaskCheckpoint.begin(0, 2000, "new-owner", -42, 0, 1);
        SummaryTaskCheckpoint.clearOwner(0, 1000);
        check(SummaryTaskCheckpoint.read(0, 2000).taskId.equals("new-owner"), "old owner cleanup isolated");
        prefs.values.put("local_ai_task_checkpoint_2000", "broken");
        rejects(() -> SummaryTaskCheckpoint.read(0, 2000), "corruption not silently mistaken for a clean shutdown");
        SummaryTaskCheckpoint.clearOwner(0, 2000);
        check(SummaryTaskCheckpoint.read(0, 2000) == null, "explicit dismissal repairs marker");
        System.out.println("SummaryTaskCheckpointTest: " + assertions + " assertions passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
