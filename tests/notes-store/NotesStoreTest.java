package org.telegram.messenger;

import android.content.Context;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class NotesStoreTest {
    private static int assertions;
    private static File directory;
    private static NotesStore store;

    public static void main(String[] args) throws Exception {
        try {
            crudAndOrdering();
            corruptionIsNeverReset();
            boundsProtectOldFile();
            atomicFailuresAndRecovery();
            System.out.println("NotesStoreTest passed: " + assertions + " assertions");
        } finally {
            if (directory != null) removeTree(directory.toPath());
        }
    }

    private static void fresh() throws Exception {
        if (directory != null) removeTree(directory.toPath());
        directory = Files.createTempDirectory("shiye-notes-fixture-").toFile();
        resetSingleton();
    }

    private static void resetSingleton() throws Exception {
        Field singleton = NotesStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        store = NotesStore.getInstance(new Context(directory));
        AtomicFile.fault = null;
    }

    private static void removeTree(Path path) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            for (Path child : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(child);
        }
    }

    private static File file() { return new File(directory, "shiye_notes.json"); }
    private static NotesStore.Note note(String id, String title, String body) {
        return new NotesStore.Note(id, title, body, 0, 0);
    }
    private static <T> Result<T> result() { return new Result<>(); }
    private static List<NotesStore.Note> load() throws Exception {
        Result<List<NotesStore.Note>> result = result(); store.load(result); return result.success();
    }
    private static NotesStore.Note save(NotesStore.Note note) throws Exception {
        Result<NotesStore.Note> result = result(); store.save(note, result); return result.success();
    }
    private static void delete(String id) throws Exception {
        Result<Void> result = result(); store.delete(id, result); result.success();
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void crudAndOrdering() throws Exception {
        fresh();
        List<NotesStore.Note> empty = load();
        check(empty.isEmpty() && !file().exists(), "first load is empty without creating a file");
        delete("missing");
        check(!file().exists(), "absent delete does not create a file");
        try { empty.add(note("x", "", "")); throw new AssertionError("mutable snapshot"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        NotesStore.Note first = save(note("stable-uuid", "标题 📝", "正文\n\"quoted\" \\ slash\t\u0000"));
        check(first.createdAt > 0 && first.updatedAt >= first.createdAt, "new timestamps assigned");
        NotesStore.Note edited = save(new NotesStore.Note(first.id, "编辑", first.body, 0, 0));
        check(edited.createdAt == first.createdAt, "upsert retains original creation date");
        check(load().size() == 1 && load().get(0).title.equals("编辑"), "fixed ID upsert does not duplicate");
        resetSingleton();
        check(load().get(0).body.equals(first.body), "unicode and escapes round trip after reopening store");
        save(note("second", "second title", ""));
        delete(first.id);
        check(load().size() == 1 && load().get(0).id.equals("second"), "delete persists just the requested ID");
        delete("already-absent");
        check(load().size() == 1, "delete is idempotent");

        List<Result<NotesStore.Note>> results = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Result<NotesStore.Note> result = result(); results.add(result);
            store.save(note("queue-" + i, "title", "" + i), result);
        }
        Result<List<NotesStore.Note>> afterWrites = result(); store.load(afterWrites);
        for (Result<NotesStore.Note> result : results) result.success();
        check(afterWrites.success().size() == 26, "queued writes are merged with latest committed state");
        Result<NotesStore.Note> beforeDelete = result();
        Result<Void> deletion = result();
        Result<NotesStore.Note> afterDelete = result();
        store.save(note("same", "old", "old"), beforeDelete);
        store.delete("same", deletion);
        store.save(note("same", "new", "new"), afterDelete);
        Result<List<NotesStore.Note>> finalLoad = result(); store.load(finalLoad);
        beforeDelete.success(); deletion.success(); afterDelete.success();
        List<NotesStore.Note> ordered = finalLoad.success();
        check(ordered.stream().filter(n -> n.id.equals("same") && n.body.equals("new")).count() == 1,
                "save-delete-save order is preserved");
        for (int i = 1; i < ordered.size(); i++) {
            check(ordered.get(i - 1).updatedAt >= ordered.get(i).updatedAt, "newest-first ordering");
        }
    }

    private static String jsonNote(String id, String body) {
        return new JSONObject().put("id", id).put("title", "title").put("body", body)
                .put("createdAt", 1).put("updatedAt", 1).toString();
    }

    private static void corruptionIsNeverReset() throws Exception {
        fresh();
        String valid = jsonNote("a", "body");
        String[] invalid = {"", "{broken", "[]", "{\"version\":2,\"notes\":[]}",
                "{\"notes\":[]}", "{\"version\":1,\"notes\":[],\"unknown\":0}",
                "{\"version\":1,\"notes\":[" + valid + "," + valid + "]}",
                "{\"version\":1,\"notes\":[{\"id\":\"a\"}]}",
                "{\"version\":1,\"notes\":[" + valid.replace("\"body\":\"body\"", "\"body\":null") + "]}",
                "{\"version\":1,\"notes\":[" + valid.replace("\"updatedAt\":1", "\"updatedAt\":-1") + "]}",
                "{\"version\":1,\"notes\":[" + valid.replace("\"createdAt\":1", "\"createdAt\":1.5") + "]}",
                "{\"version\":1,\"notes\":[]} trailing"};
        for (String bad : invalid) {
            byte[] original = bad.getBytes(StandardCharsets.UTF_8);
            Files.write(file().toPath(), original);
            Result<List<NotesStore.Note>> read = result(); store.load(read); read.error();
            Result<NotesStore.Note> write = result(); store.save(note("attempt", "draft", "retained"), write); write.error();
            Result<Void> remove = result(); store.delete("a", remove); remove.error();
            check(Arrays.equals(original, Files.readAllBytes(file().toPath())), "corrupt bytes preserved");
        }
        byte[] badUtf8 = {(byte) 0xc3, (byte) 0x28};
        Files.write(file().toPath(), badUtf8);
        Result<List<NotesStore.Note>> invalidEncoding = result(); store.load(invalidEncoding); invalidEncoding.error();
        check(Arrays.equals(badUtf8, Files.readAllBytes(file().toPath())), "invalid UTF-8 is retained");
    }

    private static void boundsProtectOldFile() throws Exception {
        fresh();
        save(note("existing", "saved", "saved"));
        byte[] previous = Files.readAllBytes(file().toPath());
        NotesStore.Note[] invalid = {note("a", "x".repeat(NotesStore.MAX_TITLE_LENGTH + 1), ""),
                note("a", "", "x".repeat(NotesStore.MAX_BODY_LENGTH + 1)),
                note("x".repeat(NotesStore.MAX_ID_LENGTH + 1), "", ""), note("../bad", "", ""),
                note("a", "", "\ud800"), new NotesStore.Note("a", null, "", 0, 0)};
        for (NotesStore.Note bad : invalid) {
            Result<NotesStore.Note> write = result(); store.save(bad, write); write.error();
            check(Arrays.equals(previous, Files.readAllBytes(file().toPath())), "invalid draft does not alter disk");
        }
        StringBuilder max = new StringBuilder("{\"version\":1,\"notes\":[");
        for (int i = 0; i < NotesStore.MAX_NOTES; i++) {
            if (i > 0) max.append(','); max.append(jsonNote("count-" + i, ""));
        }
        max.append("]}");
        Files.writeString(file().toPath(), max);
        Result<NotesStore.Note> overCount = result(); store.save(note("one-too-many", "", ""), overCount); overCount.error();
        check(load().size() == NotesStore.MAX_NOTES, "full collection remains readable");
        save(note("count-0", "still editable", ""));
        check(load().size() == NotesStore.MAX_NOTES, "upsert allowed at note count limit");
        String tooMany = max.substring(0, max.length() - 2) + ',' + jsonNote("overflow", "") + "]}";
        Files.writeString(file().toPath(), tooMany);
        Result<List<NotesStore.Note>> countLoad = result(); store.load(countLoad); countLoad.error();

        // 41 maximum ASCII bodies fit; the 42nd exceeds encoded file capacity.
        String body = "x".repeat(NotesStore.MAX_BODY_LENGTH);
        StringBuilder large = new StringBuilder("{\"version\":1,\"notes\":[");
        for (int i = 0; i < 41; i++) {
            if (i > 0) large.append(','); large.append(jsonNote("large-" + i, body));
        }
        large.append("]}");
        Files.writeString(file().toPath(), large);
        byte[] beforeCapacity = Files.readAllBytes(file().toPath());
        Result<NotesStore.Note> tooLarge = result(); store.save(note("large-new", "", body), tooLarge); tooLarge.error();
        check(Arrays.equals(beforeCapacity, Files.readAllBytes(file().toPath())), "aggregate encoded bound checked before transaction");
        try (RandomAccessFile oversized = new RandomAccessFile(file(), "rw")) { oversized.setLength(NotesStore.MAX_FILE_BYTES + 1L); }
        Result<List<NotesStore.Note>> oversizedRead = result(); store.load(oversizedRead); oversizedRead.error();
        check(file().length() == NotesStore.MAX_FILE_BYTES + 1L, "oversized input is not reset");
    }

    private static void atomicFailuresAndRecovery() throws Exception {
        fresh();
        save(note("existing", "saved", "original"));
        byte[] original = Files.readAllBytes(file().toPath());
        for (String fault : new String[] {"start", "write", "finish", "rename"}) {
            AtomicFile.fault = fault;
            Result<NotesStore.Note> write = result(); store.save(note("existing", "changed", "unsaved"), write); write.error();
            check(Arrays.equals(original, Files.readAllBytes(file().toPath())), fault + " failure retains committed file");
            AtomicFile.fault = null;
            check(load().get(0).body.equals("original"), fault + " failure is recoverable");
        }
        AtomicFile.fault = "write";
        Result<Void> failedDelete = result(); store.delete("existing", failedDelete); failedDelete.error();
        AtomicFile.fault = null;
        check(load().size() == 1, "failed delete preserves note");
        Files.write(new File(file() + ".bak").toPath(), original);
        Files.writeString(file().toPath(), "incomplete legacy transaction");
        check(load().get(0).body.equals("original"), "AtomicFile legacy backup recovered");
        fresh();
        File pending = new File(file() + ".new");
        Files.writeString(pending.toPath(), "incomplete first transaction");
        Result<List<NotesStore.Note>> orphanRead = result(); store.load(orphanRead); orphanRead.error();
        Result<NotesStore.Note> orphanWrite = result(); store.save(note("new", "draft", ""), orphanWrite); orphanWrite.error();
        check(!file().exists() && Files.readString(pending.toPath()).equals("incomplete first transaction"),
                "orphan first transaction is not silently replaced");
    }

    private static final class Result<T> implements NotesStore.Callback<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        public void onSuccess(T value) {
            if (Thread.currentThread() != Looper.getMainLooper().thread) {
                future.completeExceptionally(new AssertionError("success callback not on main thread")); return;
            }
            future.complete(value);
        }
        public void onError(String message) {
            if (Thread.currentThread() != Looper.getMainLooper().thread) {
                future.completeExceptionally(new AssertionError("error callback not on main thread")); return;
            }
            future.completeExceptionally(new IOException(message));
        }
        T success() throws Exception { return future.get(30, TimeUnit.SECONDS); }
        void error() throws Exception {
            try { success(); throw new AssertionError("expected failure"); }
            catch (ExecutionException expected) {
                check(expected.getCause() instanceof IOException && expected.getCause().getMessage() != null,
                        "actionable error delivered on main thread");
            }
        }
    }
}
