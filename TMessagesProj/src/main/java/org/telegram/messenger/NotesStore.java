/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installation-local notes. No account, network, backup, or Activity lifetime dependency. */
public final class NotesStore {
    public static final int MAX_NOTES = 1000;
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 200000;
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    private static NotesStore instance;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Never shut down when a screen is closed: already submitted edits still finish.
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ShiyeNotesIO");
        thread.setDaemon(true);
        return thread;
    });

    public static synchronized NotesStore getInstance(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        if (instance == null) instance = new NotesStore(context);
        return instance;
    }

    private NotesStore(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
    }

    public static final class Note {
        public final String id;
        public final String title;
        public final String body;
        public final long createdAt;
        public final long updatedAt;

        public Note(String id, String title, String body, long createdAt, long updatedAt) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public interface Callback<T> {
        void onSuccess(T value);
        /** The UI must retain its draft until onSuccess, including after this callback. */
        void onError(String message);
    }

    /** Returns a detached immutable list, newest modification first. Missing storage is empty. */
    public void load(Callback<List<Note>> callback) {
        enqueue(callback, "无法读取本地笔记，原文件已保留，请稍后重试。", () -> snapshot(readNotes(location())));
    }

    /** Upserts one fixed ID; never writes a list captured by the UI or by an earlier load. */
    public void save(Note note, Callback<Note> callback) {
        enqueue(callback, "笔记保存失败，请保留编辑内容后重试。", () -> {
            validate(note);
            AtomicFile file = location();
            List<Note> notes = readNotes(file);
            int position = -1;
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id.equals(note.id)) {
                    position = i;
                    break;
                }
            }
            if (position < 0 && notes.size() >= MAX_NOTES) {
                throw new StoreException("笔记数量已达上限（" + MAX_NOTES + " 条），请先删除不需要的笔记。");
            }
            long now = System.currentTimeMillis();
            long createdAt = position < 0 ? (note.createdAt > 0 ? note.createdAt : now) : notes.get(position).createdAt;
            long updatedAt = Math.max(now, createdAt);
            if (position >= 0) updatedAt = Math.max(updatedAt, notes.get(position).updatedAt);
            Note saved = new Note(note.id, note.title, note.body, createdAt, updatedAt);
            if (position < 0) notes.add(saved); else notes.set(position, saved);
            writeNotes(file, notes);
            return saved;
        });
    }

    /** Deleting an absent ID succeeds, but a corrupt file always fails without resetting it. */
    public void delete(String id, Callback<Void> callback) {
        enqueue(callback, "笔记删除失败，请稍后重试。", () -> {
            validateId(id);
            AtomicFile file = location();
            List<Note> notes = readNotes(file);
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id.equals(id)) {
                    notes.remove(i);
                    writeNotes(file, notes);
                    break;
                }
            }
            return null;
        });
    }

    private interface Operation<T> { T run() throws Exception; }

    private <T> void enqueue(Callback<T> callback, String fallback, Operation<T> operation) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        io.execute(() -> {
            final T value;
            try {
                value = operation.run();
            } catch (Exception error) {
                String message = error instanceof StoreException ? error.getMessage() : fallback;
                mainHandler.post(() -> callback.onError(message));
                return;
            }
            mainHandler.post(() -> callback.onSuccess(value));
        });
    }

    private AtomicFile location() throws IOException {
        File directory = context.getNoBackupFilesDir();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Local storage unavailable");
        }
        return new AtomicFile(new File(directory, "shiye_notes.json"));
    }

    private static List<Note> readNotes(AtomicFile file) throws IOException {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base + ".bak").exists()) {
            // A leftover first-write transaction is not a new, empty collection.
            if (new File(base + ".new").exists()) throw new IOException("Incomplete notes transaction");
            return new ArrayList<>();
        }
        try (FileInputStream input = file.openRead()) {
            if (input.getChannel().size() > MAX_FILE_BYTES) throw capacityError();
            InputStream bounded = new FilterInputStream(input) {
                private long count;
                private void check(int read) throws IOException {
                    if (read > 0 && (count += read) > MAX_FILE_BYTES) throw capacityError();
                }
                @Override public int read() throws IOException {
                    int value = in.read();
                    check(value < 0 ? 0 : 1);
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    check(count);
                    return count;
                }
            };
            try (JsonReader reader = new JsonReader(new InputStreamReader(bounded,
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                reader.setLenient(false);
                return decode(reader);
            }
        }
    }

    // Streaming decoding bounds note count before allocating an unbounded JSON object graph.
    private static List<Note> decode(JsonReader reader) throws IOException {
        List<Note> notes = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        Set<String> ids = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!fields.add(name)) throw invalidFile();
            if ("version".equals(name)) {
                if (readLong(reader) != 1) throw invalidFile();
            } else if ("notes".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    if (notes.size() >= MAX_NOTES) throw capacityError();
                    Note note = readNote(reader);
                    if (!ids.add(note.id)) throw invalidFile();
                    notes.add(note);
                }
                reader.endArray();
            } else {
                throw invalidFile();
            }
        }
        reader.endObject();
        if (fields.size() != 2 || reader.peek() != JsonToken.END_DOCUMENT) throw invalidFile();
        return notes;
    }

    private static Note readNote(JsonReader reader) throws IOException {
        Set<String> fields = new HashSet<>();
        String id = null, title = null, body = null;
        long createdAt = -1, updatedAt = -1;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!fields.add(name)) throw invalidFile();
            switch (name) {
                case "id": id = readString(reader, MAX_ID_LENGTH); break;
                case "title": title = readString(reader, MAX_TITLE_LENGTH); break;
                case "body": body = readString(reader, MAX_BODY_LENGTH); break;
                case "createdAt": createdAt = readLong(reader); break;
                case "updatedAt": updatedAt = readLong(reader); break;
                default: throw invalidFile();
            }
        }
        reader.endObject();
        if (fields.size() != 5) throw invalidFile();
        Note note = new Note(id, title, body, createdAt, updatedAt);
        validate(note);
        return note;
    }

    private static String readString(JsonReader reader, int limit) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalidFile();
        String value = reader.nextString();
        if (value.length() > limit) throw capacityError();
        return value;
    }

    private static long readLong(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalidFile();
        try {
            return Long.parseLong(reader.nextString());
        } catch (NumberFormatException invalid) {
            throw invalidFile();
        }
    }

    private static List<Note> snapshot(List<Note> notes) {
        Collections.sort(notes, (left, right) -> {
            int order = Long.compare(right.updatedAt, left.updatedAt);
            return order != 0 ? order : left.id.compareTo(right.id);
        });
        return Collections.unmodifiableList(new ArrayList<>(notes));
    }

    private static void writeNotes(AtomicFile file, List<Note> notes) throws IOException {
        // Serialize and check the full encoded byte limit before opening a write transaction.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (buffer.size() >= MAX_FILE_BYTES) throw capacityError();
                buffer.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (length > MAX_FILE_BYTES - buffer.size()) throw capacityError();
                buffer.write(bytes, offset, length);
            }
        };
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(bounded, StandardCharsets.UTF_8))) {
            writer.beginObject().name("version").value(1).name("notes").beginArray();
            for (Note note : snapshot(notes)) {
                writer.beginObject().name("id").value(note.id).name("title").value(note.title)
                        .name("body").value(note.body).name("createdAt").value(note.createdAt)
                        .name("updatedAt").value(note.updatedAt).endObject();
            }
            writer.endArray().endObject();
        }
        byte[] bytes = buffer.toByteArray();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
            // finishWrite closes the stream. Never failWrite a committed/closed transaction.
            output = null;
            verifyCommit(file.getBaseFile(), bytes);
        } catch (IOException | RuntimeException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    // Android AtomicFile may log a failed rename instead of throwing. Do not report success.
    private static void verifyCommit(File file, byte[] expected) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int offset = 0, count;
            while ((count = input.read(buffer)) != -1) {
                if (count > expected.length - offset) throw new IOException("Notes commit mismatch");
                for (int i = 0; i < count; i++) {
                    if (buffer[i] != expected[offset++]) throw new IOException("Notes commit mismatch");
                }
            }
            if (offset != expected.length) throw new IOException("Notes commit incomplete");
        }
    }

    private static void validate(Note note) throws IOException {
        if (note == null) throw new StoreException("笔记内容无效，请保留编辑内容后重试。");
        validateId(note.id);
        validateText(note.title, MAX_TITLE_LENGTH, "标题");
        validateText(note.body, MAX_BODY_LENGTH, "正文");
        if (note.createdAt < 0 || note.updatedAt < note.createdAt) throw invalidFile();
    }

    private static void validateId(String id) throws IOException {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) throw invalidFile();
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (!(ch >= 'a' && ch <= 'z') && !(ch >= 'A' && ch <= 'Z')
                    && !(ch >= '0' && ch <= '9') && ch != '-' && ch != '_') throw invalidFile();
        }
    }

    private static void validateText(String text, int limit, String field) throws IOException {
        if (text == null) throw invalidFile();
        if (text.length() > limit) throw new StoreException("笔记" + field + "超过长度上限（" + limit + "），请缩短后重试。");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalidFile();
            } else if (Character.isLowSurrogate(ch)) {
                throw invalidFile();
            }
        }
    }

    private static StoreException capacityError() {
        return new StoreException("本地笔记超过存储上限，原文件已保留，请减少内容后重试。");
    }

    private static StoreException invalidFile() {
        return new StoreException("笔记数据格式无效，原文件已保留，请勿清除应用数据。");
    }

    private static final class StoreException extends IOException {
        StoreException(String message) { super(message); }
    }
}
