package org.telegram.messenger;

import android.content.Context;
import android.content.MemoryPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

public final class NotesEntryPreferencesTest {
    private static final String DELAY_KEY = "relock_delay_seconds";
    private static final String TRIGGER_KEY = "trigger_mode";
    private static int assertions;
    private static MemoryPreferences preferences;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "preferences-worker"));
    private static final ExecutorService reader = Executors.newSingleThreadExecutor(task -> new Thread(task, "preferences-reader"));

    public static void main(String[] args) throws Exception {
        try {
            defaultsAndBounds();
            invalidPersistentValues();
            failuresRollbackMemory();
            readsSeeOnlyConfirmedChanges();
            firstReadRacingWithCommit();
            legacyTriggerContract();
            System.out.println("NotesEntryPreferencesTest passed: " + assertions + " assertions");
        } finally {
            writer.shutdownNow(); reader.shutdownNow();
        }
    }

    private static void fresh() throws Exception {
        // Separate fixtures model separate process starts; production does not expose test hooks.
        for (Field field : NotesEntryPreferences.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                    && field.getType() == Integer.class) {
                field.setAccessible(true); field.set(null, null);
            }
        }
        Context context = new Context();
        ApplicationLoader.applicationContext = context;
        preferences = context.preferences;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T background(Callable<T> action) throws Exception {
        return writer.submit(action).get(5, TimeUnit.SECONDS);
    }

    private static void defaultsAndBounds() throws Exception {
        fresh();
        check(NotesEntryPreferences.getRelockDelaySeconds() == 30, "default delay is 30 seconds");
        check(preferences.editCalls == 0, "reading defaults does not write preferences");
        for (int seconds : new int[] {0, 1, 30, 300, 86400}) {
            check(background(() -> NotesEntryPreferences.setRelockDelaySeconds(seconds)), "valid delay commits");
            check(NotesEntryPreferences.getRelockDelaySeconds() == seconds, "valid delay read back");
            check(Objects.equals(preferences.diskValue(DELAY_KEY), seconds), "valid delay persisted as integer");
        }
        int editsBefore = preferences.editCalls;
        Map<String, Object> diskBefore = preferences.diskSnapshot();
        for (int seconds : new int[] {-1, -30, 86401, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            try {
                background(() -> NotesEntryPreferences.setRelockDelaySeconds(seconds));
                throw new AssertionError("invalid setter accepted " + seconds);
            } catch (ExecutionException expected) {
                check(expected.getCause() instanceof IllegalArgumentException, "invalid delay rejected explicitly");
            }
            check(preferences.editCalls == editsBefore, "invalid delay never starts editing");
            check(NotesEntryPreferences.getRelockDelaySeconds() == 86400, "invalid delay cannot change confirmed state");
            check(preferences.diskSnapshot().equals(diskBefore), "invalid delay cannot change disk");
        }
        check(preferences.applyCalls == 0, "save result uses commit, not unconfirmed apply");
        check(preferences.commitThreads.stream().allMatch("preferences-worker"::equals), "caller runs commits on background worker");
    }

    private static void invalidPersistentValues() throws Exception {
        for (Object bad : new Object[] {-1, 86401, Integer.MAX_VALUE, "30", 30L, false}) {
            fresh(); preferences.seed(DELAY_KEY, bad);
            check(NotesEntryPreferences.getRelockDelaySeconds() == 30, "invalid persisted delay falls back to 30");
            check(preferences.editCalls == 0, "fallback read does not overwrite malformed storage");
        }
    }

    private static void failuresRollbackMemory() throws Exception {
        for (int failures : new int[] {1, 2}) {
            fresh();
            check(background(() -> NotesEntryPreferences.setRelockDelaySeconds(120)), "seed confirmed delay");
            preferences.successfulDiskWrites.clear();
            preferences.failNextCommits = failures;
            check(!background(() -> NotesEntryPreferences.setRelockDelaySeconds(360)), "failed commit reports false");
            check(NotesEntryPreferences.getRelockDelaySeconds() == 120, "failed save retains confirmed getter value");
            check(Objects.equals(preferences.memoryValue(DELAY_KEY), 120), "failed save restores SharedPreferences memory");
            check(Objects.equals(preferences.diskValue(DELAY_KEY), 120), "failed save leaves old disk value");
            check(preferences.successfulDiskWrites.stream().noneMatch(snapshot -> Objects.equals(snapshot.get(DELAY_KEY), 360)),
                    "unconfirmed candidate never reaches successful disk write");
            check(background(() -> NotesEntryPreferences.setRelockDelaySeconds(600)), "saving recovers after failure");
            check(NotesEntryPreferences.getRelockDelaySeconds() == 600, "successful retry publishes new value");
        }
        fresh(); preferences.failNextCommits = 2;
        check(!background(() -> NotesEntryPreferences.setRelockDelaySeconds(0)), "first setting failure reports false");
        check(NotesEntryPreferences.getRelockDelaySeconds() == 30, "first failure leaves default effective delay");
        check(preferences.diskValue(DELAY_KEY) == null, "double failed first write does not create persisted key");
    }

    private static void readsSeeOnlyConfirmedChanges() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            fresh();
            background(() -> NotesEntryPreferences.setRelockDelaySeconds(90));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            preferences.nextCommitEntered = entered;
            preferences.releaseNextCommit = release;
            preferences.failNextCommits = fail ? 1 : 0;
            Future<Boolean> pending = writer.submit(() -> NotesEntryPreferences.setRelockDelaySeconds(180));
            try {
                check(entered.await(2, TimeUnit.SECONDS), "write reached pending commit");
                check(Objects.equals(preferences.memoryValue(DELAY_KEY), 180), "test models commit's early memory publication");
                int visible = reader.submit(NotesEntryPreferences::getRelockDelaySeconds).get(1, TimeUnit.SECONDS);
                check(visible == 90, "getter stays nonblocking and returns previous confirmed value while disk pending");
            } finally { release.countDown(); }
            check(pending.get(3, TimeUnit.SECONDS) == !fail, "pending commit returns its disk result");
            check(NotesEntryPreferences.getRelockDelaySeconds() == (fail ? 90 : 180), "only successful commit publishes candidate");
        }
    }

    private static void legacyTriggerContract() throws Exception {
        fresh();
        check(NotesEntryPreferences.getTriggerMode() == NotesEntryPreferences.LONG_PRESS_TITLE, "legacy default unchanged");
        check(background(() -> NotesEntryPreferences.setTriggerMode(NotesEntryPreferences.FIVE_TAPS)), "five taps saves");
        check(NotesEntryPreferences.getTriggerMode() == NotesEntryPreferences.FIVE_TAPS, "five taps reads back");
        background(() -> NotesEntryPreferences.setRelockDelaySeconds(42));
        check(NotesEntryPreferences.getTriggerMode() == NotesEntryPreferences.FIVE_TAPS, "delay update does not change trigger");
        for (int failures : new int[] {1, 2}) {
            preferences.failNextCommits = failures;
            check(!background(() -> NotesEntryPreferences.setTriggerMode(NotesEntryPreferences.LONG_PRESS_TITLE)), "trigger failure reports false");
            check(NotesEntryPreferences.getTriggerMode() == NotesEntryPreferences.FIVE_TAPS, "trigger getter rolls back");
            check(Objects.equals(preferences.memoryValue(TRIGGER_KEY), NotesEntryPreferences.FIVE_TAPS), "trigger memory rolls back");
            check(Objects.equals(preferences.diskValue(TRIGGER_KEY), NotesEntryPreferences.FIVE_TAPS), "trigger disk unchanged");
        }
        check(background(() -> NotesEntryPreferences.setTriggerMode(NotesEntryPreferences.LONG_PRESS_TITLE)), "long press still saves");
        check(NotesEntryPreferences.getRelockDelaySeconds() == 42, "trigger update does not change delay");
        int editsBefore = preferences.editCalls;
        try {
            background(() -> NotesEntryPreferences.setTriggerMode(17));
            throw new AssertionError("invalid trigger accepted");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "invalid trigger rejected");
        }
        check(preferences.editCalls == editsBefore, "invalid trigger never edits");
        fresh(); preferences.seed(TRIGGER_KEY, 17);
        check(NotesEntryPreferences.getTriggerMode() == NotesEntryPreferences.LONG_PRESS_TITLE, "invalid old trigger falls back");
    }

    private static void firstReadRacingWithCommit() throws Exception {
        for (boolean trigger : new boolean[] {false, true}) {
            fresh();
            CountDownLatch readEntered = new CountDownLatch(1), releaseRead = new CountDownLatch(1);
            CountDownLatch commitEntered = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
            preferences.nextIntReadEntered = readEntered;
            preferences.releaseNextIntRead = releaseRead;
            Future<Integer> firstRead = reader.submit(() -> trigger ? NotesEntryPreferences.getTriggerMode()
                    : NotesEntryPreferences.getRelockDelaySeconds());
            Future<Boolean> pending = null;
            try {
                check(readEntered.await(2, TimeUnit.SECONDS), "first getter has observed null confirmed cache");
                preferences.nextCommitEntered = commitEntered;
                preferences.releaseNextCommit = releaseCommit;
                pending = writer.submit(() -> trigger ? NotesEntryPreferences.setTriggerMode(NotesEntryPreferences.FIVE_TAPS)
                        : NotesEntryPreferences.setRelockDelaySeconds(180));
                check(commitEntered.await(2, TimeUnit.SECONDS), "setter published tentative memory after getter's cache check");
                releaseRead.countDown();
                check(firstRead.get(1, TimeUnit.SECONDS) == (trigger ? NotesEntryPreferences.LONG_PRESS_TITLE : 30),
                        "first getter racing a commit must recheck confirmed cache before returning");
            } finally {
                releaseRead.countDown(); releaseCommit.countDown();
                if (pending != null) pending.get(3, TimeUnit.SECONDS);
            }
        }
    }
}
