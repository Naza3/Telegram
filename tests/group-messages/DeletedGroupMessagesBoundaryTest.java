package org.telegram.messenger.groupmessages;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.UserConfig;

/** Exercises production async facade with real disk/AES and deterministic queue interleavings. */
public final class DeletedGroupMessagesBoundaryTest {
    private static int assertions;
    private static DeletedMessageStore store;
    private static File root;
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("deleted-group-boundary").toFile();
        try {
            store = new DeletedMessageStore(root, new Keys());
            Field field = DeletedGroupMessages.class.getDeclaredField("stores"); field.setAccessible(true);
            ((DeletedMessageStore[]) field.get(null))[0] = store;
            UserConfig.getInstance(0).owner = 100;
            check(!DeletedGroupMessages.isEnabled(0, 100, -42), "default disabled");
            check(!DeletedGroupMessages.isEnabled(0, 100, 42), "private scope rejected");
            enabled(true);
            check(DeletedGroupMessages.isEnabled(0, 100, -42), "enabled owned group");
            GroupMessageSettings.broken = true;
            check(!DeletedGroupMessages.isEnabled(0, 100, -42), "settings failure cannot interrupt native deletion");
            GroupMessageSettings.broken = false;
            DeletedGroupMessages.retain(0, 100, Collections.singletonList(record(1)));
            enabled(false); DispatchQueue.drain();
            check(store.read(100, -42).isEmpty(), "disable cancels queued collection");
            enabled(true);
            DeletedGroupMessages.retain(0, 100, Collections.singletonList(record(1)));
            UserConfig.getInstance(0).owner = 200; DispatchQueue.drain();
            check(store.read(100, -42).isEmpty(), "owner change cancels old capture");
            UserConfig.getInstance(0).owner = 100;
            DeletedGroupMessages.retain(0, 100, Arrays.asList(record(1), record(2)));
            DeletedGroupMessages.removeIds(0, 100, -42, Arrays.asList(-7, null, 1));
            DispatchQueue.drain();
            check(store.read(100, -42).size() == 1 && store.read(100, -42).get(0).messageId == 2,
                    "mixed pending/server ids clear the retained positive id after capture");
            enabled(false);
            final int[] callbacks = {0};
            DeletedGroupMessages.getRecords(0, 100, -42, (records, failed) -> {
                check(!failed && records.size() == 1, "disabled collection still allows reading"); callbacks[0]++;
            });
            DispatchQueue.drain(); AndroidUtilities.drain();
            check(callbacks[0] == 1, "single read callback");
            DeletedGroupMessages.getRecords(0, 100, -42, (records, failed) -> {
                check(failed && records.isEmpty(), "owner changed before UI callback cannot disclose text"); callbacks[0]++;
            });
            DispatchQueue.drain(); UserConfig.getInstance(0).owner = 200; AndroidUtilities.drain();
            check(callbacks[0] == 2, "stale read reports failure once");
            DeletedGroupMessages.clear(0, 100, -42, failed -> check(failed, "stale owner cannot clear old records"));
            DispatchQueue.drain(); AndroidUtilities.drain();
            check(store.read(100, -42).size() == 1, "stale clear leaves old data untouched");
            store.putAll(200, Collections.singletonList(new DeletedMessageRecord(200, -42, 0, 3, 100, 9, "", "new owner", 201)));
            DeletedGroupMessages.logout(0, 100); DispatchQueue.drain();
            check(store.read(100, -42).isEmpty(), "logout clears fixed old owner after slot reused");
            check(store.read(200, -42).size() == 1, "logout cannot clear replacement owner's records");
            UserConfig.getInstance(0).owner = 100; enabled(true);
            DeletedGroupMessages.retain(0, 100, Arrays.asList(record(4), new DeletedMessageRecord(100, -43, 0, 4, 100, 9, "", "other group", 200)));
            GroupMessageSettings.enabled.add("0:-43"); DispatchQueue.drain();
            DeletedGroupMessages.clear(0, 100, -42, failed -> check(!failed, "owned clear succeeds"));
            DispatchQueue.drain(); AndroidUtilities.drain();
            check(store.read(100, -42).isEmpty() && store.read(100, -43).size() == 1, "group clear preserves another group");
            File encrypted = new File(root, "deleted-messages-100.bin");
            byte[] content = Files.readAllBytes(encrypted.toPath()); content[content.length - 1] ^= 0x7f;
            Files.write(encrypted.toPath(), content);
            DeletedGroupMessages.retain(0, 100, Collections.singletonList(record(6))); DispatchQueue.drain();
            check(Arrays.equals(content, Files.readAllBytes(encrypted.toPath())), "capture failure keeps corrupt archive untouched and returns");
            DeletedGroupMessages.getRecords(0, 100, -43, (records, failed) -> check(failed && records.isEmpty(), "corruption fails closed"));
            DeletedGroupMessages.clear(0, 100, -43, failed -> check(failed, "failed group clear is reported"));
            DispatchQueue.drain(); AndroidUtilities.drain();
            DeletedGroupMessages.clear(0, 100, 0, failed -> check(!failed, "explicit owner reset recovers corrupt ciphertext"));
            DispatchQueue.drain(); AndroidUtilities.drain();
            check(store.read(100, 0).isEmpty(), "reset leaves no records");
            DeletedGroupMessages.getRecords(0, 100, 0, null);
            DeletedGroupMessages.clear(0, 100, 0, null);
            DispatchQueue.drain(); AndroidUtilities.drain();
            System.out.println("Deleted message async boundary: " + assertions + " assertions passed");
        } finally { erase(root); }
    }
    private static DeletedMessageRecord record(int id) { return new DeletedMessageRecord(100, -42, 0, id, 100, 9, "", "text " + id, 200); }
    private static void enabled(boolean value) { if (value) GroupMessageSettings.enabled.add("0:-42"); else GroupMessageSettings.enabled.remove("0:-42"); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void erase(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) erase(child); file.delete(); }
    private static final class Keys implements DeletedMessageStore.KeyProvider {
        private final Map<Long, SecretKey> keys = new HashMap<>();
        public SecretKey getKey(long owner, boolean create) throws Exception {
            if (!keys.containsKey(owner) && create) { KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(128); keys.put(owner, generator.generateKey()); }
            if (!keys.containsKey(owner)) throw new IllegalStateException("Missing test key");
            return keys.get(owner);
        }
        public void deleteKey(long owner) { keys.remove(owner); }
    }
}
