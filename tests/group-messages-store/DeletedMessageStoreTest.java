/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Real production classes, AES-GCM and filesystem I/O; no storage or crypto stubs. */
public final class DeletedMessageStoreTest {
    private static final long OWNER = 1001;
    private static final long OTHER = 2002;
    private static final long GROUP = -101;
    private static int passed;
    private static int assertions;
    private static Path root;
    private static Keys keys;
    private static DeletedMessageStore store;

    public static void main(String[] args) throws Exception {
        test("encrypted Unicode round trip across store instances", DeletedMessageStoreTest::roundTrip);
        test("owner and group filtering and newest-first stable order", DeletedMessageStoreTest::scopes);
        test("repeat deletion preserves first snapshot without rewriting", DeletedMessageStoreTest::idempotence);
        test("mixed-owner batch fails before touching storage", DeletedMessageStoreTest::ownerValidation);
        test("account and group collisions are isolated during deletion", DeletedMessageStoreTest::removeIds);
        test("date removal uses inclusive sentAt rather than deletedAt", DeletedMessageStoreTest::dateRange);
        test("sender cleanup separates user and channel identities", DeletedMessageStoreTest::senderCleanup);
        test("topic cleanup preserves other topics and groups", DeletedMessageStoreTest::topicCleanup);
        test("group and owner cleanup preserve other owners", DeletedMessageStoreTest::clearScopes);
        test("record-count capacity retains newest records", DeletedMessageStoreTest::countCapacity);
        test("real UTF-8 and GCM file-size capacity evicts oldest", DeletedMessageStoreTest::byteCapacity);
        test("failed key access preserves previous durable file", DeletedMessageStoreTest::keyFailure);
        test("failed atomic rename leaves no temporary ciphertext", DeletedMessageStoreTest::renameFailure);
        test("ciphertext tampering and truncation fail closed without overwriting", DeletedMessageStoreTest::corruption);
        test("cross-owner ciphertext replay fails even with identical keys", DeletedMessageStoreTest::ownerBinding);
        test("wrong and missing keys cannot be silently replaced", DeletedMessageStoreTest::missingKey);
        test("fresh encryption uses random nonces", DeletedMessageStoreTest::randomNonce);
        test("authenticated malformed schemas fail closed", DeletedMessageStoreTest::malformedSchema);
        test("invalid UTF-8 in authenticated text fails closed", DeletedMessageStoreTest::malformedUtf8);
        test("maximum text boundary and invalid Unicode are enforced", DeletedMessageStoreTest::recordBounds);
        test("invalid scopes never mutate good records", DeletedMessageStoreTest::invalidScopes);
        test("owner cleanup works without decrypting damaged records and clears orphan temps", DeletedMessageStoreTest::clearDamaged);
        test("cleanup revokes keys despite file failure and reports key failure", DeletedMessageStoreTest::cleanupFailure);
        test("separate store instances serialize read-modify-write", DeletedMessageStoreTest::concurrentStores);
        System.out.println("DeletedMessageStoreTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void reset() throws Exception {
        root = Files.createTempDirectory("deleted-message-store-test-");
        keys = new Keys();
        store = new DeletedMessageStore(root.toFile(), keys);
    }

    private static void roundTrip() throws Exception {
        DeletedMessageRecord original = new DeletedMessageRecord(OWNER, GROUP, 987, 13, 1700000000,
                12345, "小明\uD83D\uDE00", " PRIVATE_SOURCE_秘密\n回复 @B：好的\uD83D\uDE80 \u0000 end ", 1800000000L);
        store.putAll(OWNER, Collections.singletonList(original));
        byte[] disk = disk(OWNER);
        check(!new String(disk, StandardCharsets.UTF_8).contains("PRIVATE_SOURCE_"), "plaintext persisted");
        check(!new String(disk, StandardCharsets.UTF_8).contains(original.senderName), "sender name persisted");
        store = new DeletedMessageStore(root.toFile(), keys);
        List<DeletedMessageRecord> result = store.read(OWNER, GROUP);
        check(result.size() == 1, "missing round trip");
        DeletedMessageRecord actual = result.get(0);
        check(actual.ownerId == OWNER && actual.dialogId == GROUP && actual.topicId == 987
                && actual.messageId == 13 && actual.sentAt == 1700000000 && actual.senderUserId == 12345
                && actual.deletedAt == 1800000000L, "identity or timestamps changed");
        check(actual.text.equals(original.text) && actual.senderName.equals(original.senderName), "Unicode/text changed");
        expect(() -> result.clear(), "result is mutable");
        check(keys.createCalls == 1 && keys.readCalls == 1, "unexpected key access");
    }

    private static void scopes() throws Exception {
        store.putAll(OWNER, Arrays.asList(record(OWNER, GROUP, 1, 11), record(OWNER, -202, 1, 33),
                record(OWNER, GROUP, 2, 22)));
        store.putAll(OTHER, Collections.singletonList(record(OTHER, GROUP, 1, 99)));
        check(store.read(OWNER, 0).size() == 3, "owner count");
        check(store.read(OTHER, 0).size() == 1, "other owner count");
        List<DeletedMessageRecord> group = store.read(OWNER, GROUP);
        check(group.size() == 2 && group.get(0).messageId == 2 && group.get(1).messageId == 1, "group/order");
        check(store.read(OWNER, -999).isEmpty(), "missing group not empty");
        check(store.read(OWNER, 0).get(0).dialogId == -202, "global order");
    }

    private static void idempotence() throws Exception {
        DeletedMessageRecord first = record(OWNER, GROUP, 1, 10);
        store.putAll(OWNER, Arrays.asList(first, record(OWNER, GROUP, 1, 20)));
        byte[] before = disk(OWNER);
        DeletedMessageRecord duplicate = new DeletedMessageRecord(OWNER, GROUP, 0, 1, 5, 0,
                "altered", "changed body", 999);
        store.putAll(OWNER, Arrays.asList(duplicate, duplicate));
        check(Arrays.equals(before, disk(OWNER)), "duplicate caused rewrite");
        List<DeletedMessageRecord> records = store.read(OWNER, 0);
        check(records.size() == 1 && records.get(0).deletedAt == 10 && records.get(0).text.equals(first.text),
                "duplicate changed first capture");
    }

    private static void ownerValidation() throws Exception {
        save(1);
        byte[] before = disk(OWNER);
        expect(() -> store.putAll(OWNER, Arrays.asList(record(OWNER, GROUP, 2, 2), record(OTHER, GROUP, 3, 3))),
                "mixed-owner batch accepted");
        expect(() -> store.putAll(OWNER, Arrays.asList(record(OWNER, GROUP, 2, 2), null)), "null record accepted");
        check(Arrays.equals(before, disk(OWNER)), "bad batch mutated disk");
        check(!Files.exists(path(OTHER)), "bad batch touched other owner");
    }

    private static void removeIds() throws Exception {
        store.putAll(OWNER, Arrays.asList(record(OWNER, GROUP, 1, 1), record(OWNER, GROUP, 2, 2),
                record(OWNER, -202, 1, 3)));
        store.putAll(OTHER, Collections.singletonList(record(OTHER, GROUP, 1, 4)));
        store.removeIds(OWNER, GROUP, Arrays.asList(1, 1, 1000));
        check(store.read(OWNER, GROUP).size() == 1 && store.read(OWNER, GROUP).get(0).messageId == 2, "wrong IDs removed");
        check(store.read(OWNER, -202).size() == 1 && store.read(OTHER, GROUP).size() == 1, "collision crossed scope");
        byte[] before = disk(OWNER);
        store.removeIds(OWNER, GROUP, new int[] { 900 });
        check(Arrays.equals(before, disk(OWNER)), "missing ID rewrote file");
        store.removeIds(OWNER, GROUP, new int[] { 2 });
        check(store.read(OWNER, GROUP).isEmpty(), "int[] overload failed");
    }

    private static void dateRange() throws Exception {
        ArrayList<DeletedMessageRecord> records = new ArrayList<>();
        for (int i = 1; i <= 5; i++) records.add(record(OWNER, GROUP, i, 100 - i));
        records.add(record(OWNER, -202, 3, 1));
        store.putAll(OWNER, records);
        store.removeDateRange(OWNER, GROUP, 2, 4);
        List<DeletedMessageRecord> result = store.read(OWNER, GROUP);
        check(result.size() == 2 && result.get(0).messageId == 1 && result.get(1).messageId == 5, "wrong date basis/bounds");
        check(store.read(OWNER, -202).size() == 1, "date removal crossed group");
        store.removeDateRange(OWNER, GROUP, 0, Integer.MAX_VALUE);
        check(store.read(OWNER, GROUP).isEmpty(), "full date range failed");
    }

    private static void clearScopes() throws Exception {
        store.putAll(OWNER, Arrays.asList(record(OWNER, GROUP, 1, 1), record(OWNER, -202, 2, 2)));
        store.putAll(OTHER, Collections.singletonList(record(OTHER, GROUP, 1, 3)));
        store.clearDialog(OWNER, GROUP);
        check(store.read(OWNER, GROUP).isEmpty() && store.read(OWNER, -202).size() == 1, "group clear scope");
        check(keys.values.containsKey(OWNER), "group clear revoked owner key");
        store.clearOwner(OWNER);
        check(!Files.exists(path(OWNER)) && !keys.values.containsKey(OWNER), "owner file/key remains");
        check(store.read(OTHER, 0).size() == 1 && keys.values.containsKey(OTHER), "other owner removed");
        check(store.read(OWNER, 0).isEmpty(), "cleared owner not empty");
        store.clearOwner(OWNER);
    }

    private static void senderCleanup() throws Exception {
        store.putAll(OWNER, Arrays.asList(
                new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 55, "user", "one", 1),
                new DeletedMessageRecord(OWNER, GROUP, 0, 2, 2, 0, "channel", "two", 2, -55),
                new DeletedMessageRecord(OWNER, GROUP, 0, 3, 3, 0, "other channel", "three", 3, -66),
                new DeletedMessageRecord(OWNER, GROUP, 0, 4, 4, 0, "unknown", "four", 4),
                new DeletedMessageRecord(OWNER, -202, 0, 2, 2, 0, "channel", "five", 5, -55)));
        check(store.read(OWNER, GROUP).get(2).senderPeerId == -55, "channel sender identity lost after storage");
        store.removeSender(OWNER, GROUP, -55);
        check(store.read(OWNER, GROUP).size() == 3, "channel cleanup scope");
        check(store.read(OWNER, -202).size() == 1, "channel cleanup crossed group");
        for (DeletedMessageRecord record : store.read(OWNER, GROUP)) check(record.senderPeerId != -55, "target sender remains");
        store.removeSender(OWNER, GROUP, 55);
        check(store.read(OWNER, GROUP).size() == 2, "user sender cleanup failed");
        check(store.read(OWNER, GROUP).get(0).senderPeerId == 0
                && store.read(OWNER, GROUP).get(1).senderPeerId == -66, "anonymous identities conflated");
        expect(() -> store.removeSender(OWNER, GROUP, 0), "unknown sender deletion accepted");
        expect(() -> store.removeSender(OWNER, GROUP, Long.MIN_VALUE), "invalid sender accepted");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 55, "", "x", 1, -55), "inconsistent user/peer accepted");
    }

    private static void topicCleanup() throws Exception {
        store.putAll(OWNER, Arrays.asList(
                new DeletedMessageRecord(OWNER, GROUP, 10, 1, 1, 55, "", "one", 1),
                new DeletedMessageRecord(OWNER, GROUP, 11, 2, 2, 55, "", "two", 2),
                new DeletedMessageRecord(OWNER, GROUP, 0, 3, 3, 55, "", "three", 3),
                new DeletedMessageRecord(OWNER, -202, 10, 4, 4, 55, "", "four", 4)));
        store.removeTopic(OWNER, GROUP, 10);
        check(store.read(OWNER, GROUP).size() == 2 && store.read(OWNER, -202).size() == 1, "topic scope mismatch");
        for (DeletedMessageRecord record : store.read(OWNER, GROUP)) check(record.topicId != 10, "topic still present");
        expect(() -> store.removeTopic(OWNER, GROUP, 0), "zero topic accepted");
        expect(() -> store.removeTopic(OWNER, 0, 10), "topic clear all groups accepted");
    }

    private static void countCapacity() throws Exception {
        ArrayList<DeletedMessageRecord> records = new ArrayList<>();
        for (int i = 1; i <= DeletedMessageStore.MAX_RECORDS + 17; i++) records.add(record(OWNER, GROUP, i, i));
        store.putAll(OWNER, records);
        List<DeletedMessageRecord> result = store.read(OWNER, GROUP);
        check(result.size() == DeletedMessageStore.MAX_RECORDS, "count bound not enforced");
        check(result.get(0).messageId == 2017 && result.get(1999).messageId == 18, "wrong count eviction order");
        store.putAll(OWNER, Collections.singletonList(record(OWNER, GROUP, 9000, 1)));
        check(store.read(OWNER, GROUP).get(1999).messageId == 18, "old addition evicted newer data");
    }

    private static void byteCapacity() throws Exception {
        String large = repeat("汉", DeletedMessageRecord.MAX_TEXT_CHARS);
        ArrayList<DeletedMessageRecord> records = new ArrayList<>();
        for (int i = 1; i <= 120; i++) records.add(new DeletedMessageRecord(OWNER, GROUP, 0, i, i, 5, "sender", large, i));
        store.putAll(OWNER, records);
        List<DeletedMessageRecord> result = store.read(OWNER, GROUP);
        int recordBytes = 64 + "sender".length() + 3 * large.length();
        int expected = (DeletedMessageStore.MAX_STORAGE_BYTES - 36 - 8) / recordBytes;
        check(result.size() == expected, "byte capacity doesn't match serialized data");
        check(result.get(0).messageId == 120 && result.get(result.size() - 1).messageId == 121 - expected,
                "wrong byte eviction order");
        check(Files.size(path(OWNER)) <= DeletedMessageStore.MAX_STORAGE_BYTES, "ciphertext exceeds bound");
        check(Files.size(path(OWNER)) == 44L + result.size() * recordBytes, "unexpected serialized byte count");
        for (DeletedMessageRecord record : result) check(record.text.equals(large), "capacity truncated retained text");
    }

    private static void keyFailure() throws Exception {
        save(1);
        byte[] before = disk(OWNER);
        keys.failCreate = true;
        expect(() -> save(2), "encryption failure ignored");
        check(Arrays.equals(before, disk(OWNER)), "encryption failure replaced old file");
        keys.failCreate = false;
        check(store.read(OWNER, 0).size() == 1, "failed save added record");
        keys.failRead = true;
        expect(() -> save(3), "decryption failure replaced old data");
        check(Arrays.equals(before, disk(OWNER)), "read failure rewrote data");
        check(noTemporaryFiles(), "failure left temp file");
    }

    private static void renameFailure() throws Exception {
        Files.createDirectory(path(OWNER));
        Files.write(path(OWNER).resolve("keep"), new byte[] { 1 });
        expect(() -> save(1), "directory target accepted");
        check(Files.isDirectory(path(OWNER)) && Files.exists(path(OWNER).resolve("keep")), "failed write removed target");
        check(noTemporaryFiles(), "failed write left temp file");
        // Force a new target collision only when encryption is about to happen, after read has finished.
        deleteTree(path(OWNER).toFile());
        keys.onCreate = () -> {
            try { Files.createDirectory(path(OWNER)); Files.write(path(OWNER).resolve("keep"), new byte[] { 1 }); }
            catch (IOException error) { throw new RuntimeException(error); }
        };
        expect(() -> save(2), "rename collision accepted");
        check(Files.exists(path(OWNER).resolve("keep")), "rename failure damaged target");
        check(noTemporaryFiles(), "failed rename left temp file");
    }

    private static void corruption() throws Exception {
        save(1);
        byte[] original = disk(OWNER);
        List<byte[]> broken = new ArrayList<>();
        byte[] altered = original.clone(); altered[altered.length - 1] ^= 1; broken.add(altered);
        byte[] iv = original.clone(); iv[8] ^= 1; broken.add(iv);
        byte[] version = original.clone(); version[7] = 2; broken.add(version);
        broken.add(Arrays.copyOf(original, original.length - 1));
        broken.add(new byte[0]);
        broken.add(new byte[DeletedMessageStore.MAX_STORAGE_BYTES + 1]);
        for (byte[] bytes : broken) {
            Files.write(path(OWNER), bytes);
            expect(() -> store.read(OWNER, 0), "corrupt file returned records");
            expect(() -> save(2), "corrupt file overwritten");
            expect(() -> store.clearDialog(OWNER, GROUP), "partial clear accepted corrupt data");
            check(Arrays.equals(bytes, disk(OWNER)), "corrupt source file changed");
        }
    }

    private static void ownerBinding() throws Exception {
        save(1);
        keys.values.put(OTHER, keys.values.get(OWNER));
        Files.write(path(OTHER), disk(OWNER));
        expect(() -> store.read(OTHER, 0), "owner AAD not enforced");
        check(store.read(OWNER, 0).size() == 1, "source owner damaged");
    }

    private static void missingKey() throws Exception {
        save(1);
        byte[] before = disk(OWNER);
        SecretKey correct = keys.values.remove(OWNER);
        int creates = keys.createCalls;
        expect(() -> store.read(OWNER, 0), "missing key accepted");
        expect(() -> save(2), "missing key silently regenerated");
        check(keys.createCalls == creates && !keys.values.containsKey(OWNER), "read generated a key");
        keys.values.put(OWNER, new SecretKeySpec(new byte[16], "AES"));
        expect(() -> store.read(OWNER, 0), "wrong key accepted");
        check(Arrays.equals(before, disk(OWNER)), "key failure changed file");
        keys.values.put(OWNER, correct);
        check(store.read(OWNER, 0).size() == 1, "restore original key failed");
    }

    private static void randomNonce() throws Exception {
        save(1);
        byte[] nonce = Arrays.copyOfRange(disk(OWNER), 8, 20);
        save(2);
        check(!Arrays.equals(nonce, Arrays.copyOfRange(disk(OWNER), 8, 20)), "AES-GCM nonce reused");
        check(noTemporaryFiles(), "successful replacement left temp file");
    }

    private static void malformedSchema() throws Exception {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(plaintext(2, 0));
        invalid.add(plaintext(1, -1));
        invalid.add(plaintext(1, DeletedMessageStore.MAX_RECORDS + 1));
        invalid.add(plaintext(1, 1)); // announced but missing record
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt(1); out.writeInt(1); writeRecord(out, record(OTHER, GROUP, 1, 1));
        invalid.add(body.toByteArray());
        body.reset(); out.writeInt(1); out.writeInt(2);
        writeRecord(out, record(OWNER, GROUP, 1, 1)); writeRecord(out, record(OWNER, GROUP, 1, 2));
        invalid.add(body.toByteArray());
        body.reset(); out.writeInt(1); out.writeInt(0); out.writeByte(1);
        invalid.add(body.toByteArray());
        for (byte[] plain : invalid) {
            writeAuthenticated(plain);
            byte[] before = disk(OWNER);
            expect(() -> store.read(OWNER, 0), "invalid authenticated schema accepted");
            expect(() -> save(2), "invalid schema overwritten");
            check(Arrays.equals(before, disk(OWNER)), "invalid schema mutated");
        }
    }

    private static void malformedUtf8() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt(1); out.writeInt(1);
        out.writeLong(OWNER); out.writeLong(GROUP); out.writeLong(0);
        out.writeInt(1); out.writeInt(1); out.writeLong(0);
        out.writeInt(0); out.writeInt(2); out.write(new byte[] { (byte) 0xC0, (byte) 0xAF });
        out.writeLong(1);
        writeAuthenticated(body.toByteArray());
        expect(() -> store.read(OWNER, 0), "malformed UTF-8 replaced silently");
    }

    private static void recordBounds() throws Exception {
        DeletedMessageRecord maximum = new DeletedMessageRecord(OWNER, GROUP, Long.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Long.MAX_VALUE, repeat("a", 512), repeat("\uD83D\uDE00", 16384), Long.MAX_VALUE);
        store.putAll(OWNER, Collections.singletonList(maximum));
        check(store.read(OWNER, 0).get(0).text.equals(maximum.text), "max non-BMP text changed");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, "", repeat("a", 32769), 1), "oversized text accepted");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, repeat("a", 513), "x", 1), "oversized name accepted");
        for (String bad : Arrays.asList("", " \n\t", "\uD800", "\uDC00", "x\uD800z")) {
            expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, "", bad, 1), "invalid text accepted");
        }
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, "\uDC00", "text", 1), "invalid name accepted");
        expect(() -> new DeletedMessageRecord(0, GROUP, 0, 1, 1, 0, "", "x", 1), "bad owner");
        expect(() -> new DeletedMessageRecord(OWNER, Long.MIN_VALUE, 0, 1, 1, 0, "", "x", 1), "encrypted dialog accepted");
        expect(() -> new DeletedMessageRecord(OWNER, 100, 0, 1, 1, 0, "", "x", 1), "private dialog accepted");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, -1, 1, 1, 0, "", "x", 1), "negative topic");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, -1, 1, 0, "", "x", 1), "temporary message");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 0, 0, "", "x", 1), "zero sent time");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, -1, "", "x", 1), "negative user");
        expect(() -> new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, "", "x", 0), "zero deletion time");
        check(new DeletedMessageRecord(OWNER, GROUP, 0, 1, 1, 0, null, "x", 1).senderName.isEmpty(), "null name not normalized");
    }

    private static void invalidScopes() throws Exception {
        save(1);
        byte[] before = disk(OWNER);
        expect(() -> store.read(0, 0), "zero owner accepted");
        expect(() -> store.read(OWNER, 1), "private dialog scope accepted");
        expect(() -> store.read(OWNER, Long.MIN_VALUE), "invalid dialog scope accepted");
        expect(() -> store.clearDialog(OWNER, 0), "clear dialog=0 accepted");
        expect(() -> store.removeIds(OWNER, 0, Arrays.asList(1)), "ambiguous ID deletion accepted");
        expect(() -> store.removeIds(OWNER, GROUP, Arrays.asList(0)), "zero ID accepted");
        expect(() -> store.removeIds(OWNER, GROUP, Arrays.asList(1, null)), "null ID accepted");
        expect(() -> store.removeDateRange(OWNER, GROUP, 10, 9), "inverted dates accepted");
        expect(() -> store.removeDateRange(OWNER, GROUP, -1, 9), "negative date accepted");
        expect(() -> store.putAll(OWNER, null), "null additions accepted");
        store.putAll(OWNER, Collections.emptyList());
        store.removeIds(OWNER, GROUP, Collections.emptyList());
        check(Arrays.equals(before, disk(OWNER)), "invalid/noop operations changed file");
    }

    private static void clearDamaged() throws Exception {
        save(1);
        store.putAll(OTHER, Collections.singletonList(record(OTHER, GROUP, 1, 1)));
        Files.write(path(OWNER), new byte[] { 1, 2, 3 });
        Path orphan = root.resolve("deleted-messages-" + OWNER + ".bin.orphan.tmp");
        Path otherOrphan = root.resolve("deleted-messages-" + OTHER + ".bin.orphan.tmp");
        Files.write(orphan, new byte[] { 1 }); Files.write(otherOrphan, new byte[] { 2 });
        keys.failRead = true;
        store.clearOwner(OWNER);
        check(!Files.exists(path(OWNER)) && !Files.exists(orphan), "damaged/orphan files remain");
        check(Files.exists(path(OTHER)) && Files.exists(otherOrphan), "other owner cleanup crossed scope");
        check(!keys.values.containsKey(OWNER) && keys.values.containsKey(OTHER), "wrong key cleanup");
    }

    private static void concurrentStores() throws Exception {
        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < workers; i++) {
            final int worker = i;
            new Thread(() -> {
                try {
                    DeletedMessageStore otherStore = new DeletedMessageStore(root.toFile(), keys);
                    start.await();
                    for (int j = 1; j <= 6; j++) {
                        int id = worker * 6 + j;
                        otherStore.putAll(OWNER, Collections.singletonList(record(OWNER, GROUP, id, id)));
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { finished.countDown(); }
            }).start();
        }
        start.countDown(); finished.await();
        if (failure.get() != null) throw new AssertionError("concurrent store failure", failure.get());
        check(store.read(OWNER, 0).size() == 36, "concurrent save lost records");
        check(noTemporaryFiles(), "concurrent saves left temp files");
    }

    private static void cleanupFailure() throws Exception {
        save(1);
        Files.delete(path(OWNER));
        Files.createDirectory(path(OWNER));
        Files.write(path(OWNER).resolve("obstacle"), new byte[] { 1 });
        expect(() -> store.clearOwner(OWNER), "file deletion failure reported success");
        check(!keys.values.containsKey(OWNER), "file deletion failure left decryption key usable");
        deleteTree(path(OWNER).toFile());
        save(2);
        keys.failDelete = true;
        expect(() -> store.clearOwner(OWNER), "key deletion failure reported success");
        check(!Files.exists(path(OWNER)), "key deletion failure prevented file cleanup");
        keys.failDelete = false;
        store.clearOwner(OWNER);
        check(!keys.values.containsKey(OWNER), "retry failed to revoke key");
    }

    private static byte[] plaintext(int version, int count) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(version); output.writeInt(count);
        return bytes.toByteArray();
    }

    private static void writeRecord(DataOutputStream output, DeletedMessageRecord record) throws Exception {
        output.writeLong(record.ownerId); output.writeLong(record.dialogId); output.writeLong(record.topicId);
        output.writeInt(record.messageId); output.writeInt(record.sentAt); output.writeLong(record.senderUserId);
        byte[] name = record.senderName.getBytes(StandardCharsets.UTF_8);
        byte[] text = record.text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(name.length); output.write(name); output.writeInt(text.length); output.write(text);
        output.writeLong(record.deletedAt); output.writeLong(record.senderPeerId);
    }

    private static void writeAuthenticated(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getKey(OWNER, true));
        cipher.updateAAD(("telegram.local_deleted_messages.v1." + OWNER).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x54445231); output.writeInt(1); output.write(cipher.getIV()); output.write(cipher.doFinal(plain));
        Files.write(path(OWNER), bytes.toByteArray());
    }

    private static final class Keys implements DeletedMessageStore.KeyProvider {
        final Map<Long, SecretKey> values = new HashMap<>();
        boolean failCreate;
        boolean failRead;
        boolean failDelete;
        int createCalls;
        int readCalls;
        Runnable onCreate;
        public SecretKey getKey(long ownerId, boolean create) throws Exception {
            if (create) {
                createCalls++;
                if (failCreate) throw new IOException("injected create failure");
                if (onCreate != null) onCreate.run();
            } else {
                readCalls++;
                if (failRead) throw new IOException("injected read failure");
            }
            SecretKey key = values.get(ownerId);
            if (key == null && create) {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(128); key = generator.generateKey(); values.put(ownerId, key);
            }
            return key;
        }
        public void deleteKey(long ownerId) throws Exception {
            if (failDelete) throw new IOException("injected key deletion failure");
            values.remove(ownerId);
        }
    }

    private static DeletedMessageRecord record(long owner, long group, int id, long deletedAt) {
        return new DeletedMessageRecord(owner, group, 0, id, id, 55, "测试用户", "文字 #" + id, deletedAt);
    }
    private static void save(int id) { store.putAll(OWNER, Collections.singletonList(record(OWNER, GROUP, id, id))); }
    private static Path path(long owner) { return root.resolve("deleted-messages-" + owner + ".bin"); }
    private static byte[] disk(long owner) throws IOException { return Files.readAllBytes(path(owner)); }
    private static String repeat(String unit, int count) {
        StringBuilder result = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) result.append(unit);
        return result.toString();
    }
    private static boolean noTemporaryFiles() {
        File[] files = root.toFile().listFiles();
        if (files == null) return false;
        for (File file : files) if (file.getName().endsWith(".tmp")) return false;
        return true;
    }
    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IOException("cannot clean test directory");
    }
    private static void test(String name, Checked operation) throws Exception {
        reset();
        try { operation.run(); passed++; System.out.println("PASS " + name); }
        finally { deleteTree(root.toFile()); }
    }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static void expect(Checked operation, String message) throws Exception {
        assertions++;
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { return; }
        throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
}
