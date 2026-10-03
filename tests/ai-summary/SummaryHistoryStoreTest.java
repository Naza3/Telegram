/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the actual Store; disk atomicity and Android Keystore need device integration too. */
public final class SummaryHistoryStoreTest {
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000;
    private static final long CHAT = -100;
    private static int passed;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        test("encrypted round trip preserves immutable ordered hash references", SummaryHistoryStoreTest::roundTrip);
        test("newest-first sorting and account/chat/topic filtering", SummaryHistoryStoreTest::filtering);
        test("same ID replacement is unique and durable", SummaryHistoryStoreTest::replacement);
        test("delete and scoped clear retain unrelated records", SummaryHistoryStoreTest::deletion);
        test("owner changes cannot read or publish into another identity", SummaryHistoryStoreTest::owners);
        test("logout revokes only old-owner files and keys", SummaryHistoryStoreTest::logout);
        test("count capacity evicts oldest generated records", SummaryHistoryStoreTest::countLimit);
        test("UTF-8 byte capacity evicts old records and rejects one oversized result", SummaryHistoryStoreTest::byteLimit);
        test("encryption and atomic-write failures preserve previous records", SummaryHistoryStoreTest::failure);
        test("format and authenticated-ciphertext damage fail explicitly", SummaryHistoryStoreTest::corruption);
        test("AES-GCM owner binding, random nonces and key loss are enforced", SummaryHistoryStoreTest::cipherBoundary);
        test("invalid records and scopes cannot mutate history", SummaryHistoryStoreTest::invalid);
        System.out.println("SummaryHistoryStoreTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void roundTrip() throws Exception {
        reset();
        ArrayList<SummarySourceReference> sources = new ArrayList<>();
        sources.add(reference(22, "RAW_SOURCE_MUST_NOT_PERSIST_A"));
        sources.add(reference(11, "RAW_SOURCE_MUST_NOT_PERSIST_B"));
        SummaryHistoryStore.Record record = record("first", CHAT, 42, 10000, "完成的摘要 [m1] [m2]", true, sources);
        sources.clear();
        check(record.sources.size() == 2, "record did not copy its source list");
        expect(() -> record.sources.clear(), "record sources must be immutable");
        SummaryHistoryStore.save(ACCOUNT, OWNER, record);
        String disk = file();
        check(disk.startsWith("v1:") && !disk.contains("完成的摘要") && !disk.contains("测试群"),
                "summary metadata was written unencrypted");
        String plain = SummaryHistoryCipher.decrypt(ACCOUNT, OWNER, disk);
        check(!plain.contains("RAW_SOURCE_MUST_NOT_PERSIST") && !plain.contains("api_key")
                && !plain.contains("base_url") && !plain.contains("endpoint"), "raw source or connection metadata persisted");
        JSONObject stored = new JSONObject(plain).getJSONArray("records").getJSONObject(0);
        check(stored.getJSONArray("sources").getJSONObject(0).length() == 6,
                "source record contains fields other than identity/date/sender/hash");
        SummaryHistoryStore.Record loaded = SummaryHistoryStore.get(ACCOUNT, OWNER, "first");
        check(loaded != null && loaded.partial && loaded.generatedAtMillis == 10000 && loaded.topicId == 42,
                "record header changed");
        check(loaded.chatTitle.equals("测试群") && loaded.rangeLabel.equals("最近 20 条")
                && loaded.coverageNote.equals("覆盖说明") && loaded.templateLabel.equals("通用总结")
                && loaded.customInstructions.equals("突出结论") && loaded.model.equals("本地模型")
                && loaded.summary.equals(record.summary), "record text fields changed");
        check(loaded.sources.get(0).id == 22 && loaded.sources.get(1).id == 11
                && loaded.sources.get(0).matchesText("RAW_SOURCE_MUST_NOT_PERSIST_A"), "[mN] source order changed");
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "missing") == null, "missing ID did not return null");
        expect(() -> all().clear(), "returned list must be immutable");
    }

    private static void filtering() {
        reset();
        save(record("middle", CHAT, 42, 2000));
        save(record("oldest", CHAT, 0, 1000));
        save(record("newest", -200, 0, 3000));
        check(ids(all()).equals("newest,middle,oldest"), "history is not newest-first");
        check(ids(SummaryHistoryStore.list(ACCOUNT, OWNER, CHAT, -1)).equals("middle,oldest"), "chat/all-topic filter");
        check(ids(SummaryHistoryStore.list(ACCOUNT, OWNER, CHAT, 0)).equals("oldest"), "topic zero was treated as every topic");
        check(ids(SummaryHistoryStore.list(ACCOUNT, OWNER, CHAT, 42)).equals("middle"), "specific topic filter");
        check(SummaryHistoryStore.list(ACCOUNT, OWNER, CHAT, 43).isEmpty(), "unknown topic leaked records");
        check(SummaryHistoryStore.list(1, 2000, 0, -1).isEmpty(), "account storage was shared");
    }

    private static void replacement() {
        reset(); save(record("stable", CHAT, 0, 1000)); save(record("another", CHAT, 0, 1500));
        save(record("stable", CHAT, 42, 2000, "重做后的摘要", true, new ArrayList<>()));
        check(all().size() == 2 && ids(all()).equals("stable,another"), "same ID created duplicates or wrong order");
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "stable").summary.equals("重做后的摘要")
                && SummaryHistoryStore.get(ACCOUNT, OWNER, "stable").sources.isEmpty(), "replacement retained old fields");
    }

    private static void deletion() {
        reset(); save(record("main", CHAT, 0, 1000)); save(record("topic", CHAT, 42, 2000));
        save(record("other", -200, 0, 3000));
        SummaryHistoryStore.delete(ACCOUNT, OWNER, "absent");
        SummaryHistoryStore.clear(ACCOUNT, OWNER, CHAT, 42);
        check(ids(all()).equals("other,main"), "topic clear removed another scope");
        SummaryHistoryStore.clear(ACCOUNT, OWNER, CHAT, -1);
        check(ids(all()).equals("other"), "chat clear removed another chat");
        SummaryHistoryStore.delete(ACCOUNT, OWNER, "other");
        check(all().isEmpty() && file() == null, "last record deletion did not clear the file");
        save(record("new", CHAT, 0, 4000)); SummaryHistoryStore.clear(ACCOUNT, OWNER, 0, -1);
        check(all().isEmpty(), "account clear-all left a record");
    }

    private static void owners() {
        reset(); save(record("old", CHAT, 0, 1000)); String original = file();
        UserConfig.getInstance(ACCOUNT).setClientUserId(3000);
        expect(() -> all(), "previous owner read after slot reuse");
        expect(() -> save(record("stale", CHAT, 0, 2000)), "previous owner wrote after slot reuse");
        expect(() -> SummaryHistoryStore.delete(ACCOUNT, OWNER, "old"), "previous owner deleted after slot reuse");
        check(SummaryHistoryStore.list(ACCOUNT, 3000, 0, -1).isEmpty(), "new owner inherited old history");
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        SummaryHistoryCipher.afterEncrypt = () -> UserConfig.getInstance(ACCOUNT).setClientUserId(3000);
        expect(() -> save(record("switch-during-encrypt", CHAT, 0, 2000)), "owner change during encryption wrote history");
        SummaryHistoryCipher.afterEncrypt = null;
        check(original.equals(file()), "identity change replaced the previous owner's file");
    }

    private static void logout() {
        reset(); save(record("old", CHAT, 0, 1000));
        UserConfig.getInstance(ACCOUNT).setClientUserId(3000);
        SummaryHistoryStore.save(ACCOUNT, 3000, record("new", CHAT, 0, 2000));
        SummaryHistoryStore.clearOwner(ACCOUNT, OWNER);
        check(file() == null && SummaryHistoryStore.get(ACCOUNT, 3000, "new") != null,
                "logout cleanup deleted the new owner's record");
        check(!SummaryHistoryCipher.KEYS.containsKey("telegram.ai_summary_history.v1.0.1000"), "old owner key retained");
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER); save(record("leftover", CHAT, 0, 1000));
        SummaryHistoryStorage.failDeletes = 1;
        expect(() -> SummaryHistoryStore.clearOwner(ACCOUNT, OWNER), "failed cleanup was silently ignored");
        check(file() != null && !SummaryHistoryCipher.KEYS.containsKey("telegram.ai_summary_history.v1.0.1000"),
                "failed deletion did not still revoke the old ciphertext key");
        expect(() -> all(), "leftover file remained decryptable after logout key deletion");
        SummaryHistoryStore.clearOwner(ACCOUNT, OWNER);
    }

    private static void countLimit() {
        reset();
        for (int i = 1; i <= SummaryHistoryStore.MAX_RECORDS + 1; i++) save(record("r" + i, CHAT, 0, i));
        check(all().size() == SummaryHistoryStore.MAX_RECORDS && all().get(0).id.equals("r101"), "record limit not enforced");
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "r1") == null
                && SummaryHistoryStore.get(ACCOUNT, OWNER, "r2") != null, "oldest record was not evicted first");
    }

    private static void byteLimit() {
        reset(); String large = "中".repeat(1_100_000);
        save(record("old-large", CHAT, 0, 1000, large, false, new ArrayList<>()));
        save(record("new-large", CHAT, 0, 2000, large, false, new ArrayList<>()));
        check(ids(all()).equals("new-large"), "UTF-8 storage budget did not evict the old summary");
        check(file().getBytes(StandardCharsets.UTF_8).length <= SummaryHistoryStore.MAX_STORAGE_BYTES, "ciphertext exceeded disk bound");
        String previous = file();
        expect(() -> save(record("oversized", CHAT, 0, 3000, "大".repeat(2_200_000), false, new ArrayList<>())),
                "single oversized summary should explicitly fail");
        check(previous.equals(file()), "oversized save destroyed the old history");
    }

    private static void failure() {
        reset(); save(record("original", CHAT, 0, 1000)); String previous = file();
        SummaryHistoryCipher.failEncrypts = 1;
        expect(() -> save(record("encryption-failure", CHAT, 0, 2000)), "encryption failure fell back to plaintext");
        check(previous.equals(file()), "encryption failure mutated disk");
        SummaryHistoryStorage.failWrites = 1;
        expect(() -> save(record("write-failure", CHAT, 0, 2000)), "atomic write failure was hidden");
        check(previous.equals(file()) && all().size() == 1, "atomic write failure lost original records");
        SummaryHistoryStorage.failDeletes = 1;
        expect(() -> SummaryHistoryStore.delete(ACCOUNT, OWNER, "original"), "delete failure was hidden");
        check(previous.equals(file()), "failed deletion changed disk");
        SummaryHistoryStorage.failReads = 1;
        expect(() -> all(), "read failure silently returned empty history");
        SummaryHistoryCipher.failDecrypts = 1;
        expect(() -> save(record("decrypt-failure", CHAT, 0, 2000)), "decryption failure overwrote old history");
        check(previous.equals(file()), "decryption failure changed disk");
    }

    private static void corruption() throws Exception {
        reset(); save(record("good", CHAT, 0, 1000));
        String original = file();
        for (String broken : Arrays.asList("not encrypted", "v1:AAAA:BBBB",
                original.substring(0, original.length() - 5) + "AAAAA")) {
            setFile(broken); expect(() -> all(), "bad encryption silently returned empty");
            expect(() -> save(record("replacement", CHAT, 0, 2000)), "bad encryption was overwritten by save");
            check(file().equals(broken), "failed decode changed the damaged file");
        }
        for (String broken : Arrays.asList("not JSON", "{\"version\":99,\"records\":[]}",
                "{\"version\":1,\"records\":[{}]}")) {
            setFile(SummaryHistoryCipher.encrypt(ACCOUNT, OWNER, broken));
            expect(() -> all(), "bad JSON was accepted");
        }
        JSONObject json = new JSONObject(SummaryHistoryCipher.decrypt(ACCOUNT, OWNER, original));
        JSONArray records = json.getJSONArray("records"); records.put(records.getJSONObject(0));
        setFile(SummaryHistoryCipher.encrypt(ACCOUNT, OWNER, json.toString()));
        expect(() -> all(), "duplicate persisted IDs accepted");
        SummaryHistoryStore.clear(ACCOUNT, OWNER, 0, -1);
        check(all().isEmpty(), "explicit account clear could not recover corrupt history");
        check(!SummaryHistoryCipher.KEYS.containsKey("telegram.ai_summary_history.v1.0.1000"),
                "explicit account clear did not reset an unusable encryption key");
        save(record("recovered", CHAT, 0, 2000));
        check(ids(all()).equals("recovered"), "new history could not be saved after corrupt-history reset");
    }

    private static void cipherBoundary() {
        reset();
        String encrypted = SummaryHistoryCipher.encrypt(ACCOUNT, OWNER, "private summary");
        check(!encrypted.equals(SummaryHistoryCipher.encrypt(ACCOUNT, OWNER, "private summary")), "encryption reused a deterministic nonce");
        check(SummaryHistoryCipher.decrypt(ACCOUNT, OWNER, encrypted).equals("private summary"), "AES-GCM roundtrip failed");
        SummaryHistoryCipher.KEYS.put("telegram.ai_summary_history.v1.1.1000",
                SummaryHistoryCipher.KEYS.get("telegram.ai_summary_history.v1.0.1000"));
        expect(() -> SummaryHistoryCipher.decrypt(1, OWNER, encrypted), "AAD failed to bind account even with same AES key");
        SummaryHistoryCipher.KEYS.put("telegram.ai_summary_history.v1.0.3000",
                SummaryHistoryCipher.KEYS.get("telegram.ai_summary_history.v1.0.1000"));
        expect(() -> SummaryHistoryCipher.decrypt(ACCOUNT, 3000, encrypted), "AAD failed to bind owner even with same AES key");
        SummaryHistoryCipher.deleteKey(ACCOUNT, OWNER);
        expect(() -> SummaryHistoryCipher.decrypt(ACCOUNT, OWNER, encrypted), "deleted key resurrected history");
    }

    private static void invalid() {
        reset();
        expect(() -> record("", CHAT, 0, 1000), "empty ID accepted");
        expect(() -> record("bad", 100, 0, 1000), "user dialog accepted");
        expect(() -> record("bad", CHAT, -1, 1000), "record has ambiguous topic");
        expect(() -> record("bad", CHAT, 0, 0), "unknown creation time accepted");
        expect(() -> record("bad", CHAT, 0, 1000, "", false, new ArrayList<>()), "empty summary accepted");
        expect(() -> record("bad", -200, 0, 1000, "text", false, Arrays.asList(reference(1, "body"))), "foreign source dialog accepted");
        expect(() -> SummaryHistoryStore.list(ACCOUNT, OWNER, CHAT, -2), "bad filter scope accepted");
        expect(() -> SummaryHistoryStore.list(-1, OWNER, 0, -1), "invalid account accepted");
        check(SummaryHistoryStorage.FILES.isEmpty(), "invalid input wrote a file");
    }

    private static SummaryHistoryStore.Record record(String id, long dialog, long topic, long time) {
        return record(id, dialog, topic, time, "摘要 " + id, false, new ArrayList<>());
    }
    private static SummaryHistoryStore.Record record(String id, long dialog, long topic, long time,
            String summary, boolean partial, List<SummarySourceReference> sources) {
        return new SummaryHistoryStore.Record(id, dialog, topic, time, "测试群", "最近 20 条", "覆盖说明",
                "通用总结", "突出结论", "本地模型", summary, partial, sources);
    }
    private static SummarySourceReference reference(int id, String raw) {
        return SummarySourceReference.from(new SummaryMessage(CHAT, id, 10000, "sender", raw,
                55, 0, 0, false, false, 2, false, false));
    }
    private static List<SummaryHistoryStore.Record> all() { return SummaryHistoryStore.list(ACCOUNT, OWNER, 0, -1); }
    private static void save(SummaryHistoryStore.Record record) { SummaryHistoryStore.save(ACCOUNT, OWNER, record); }
    private static String file() { return SummaryHistoryStorage.FILES.get(SummaryHistoryStorage.key(ACCOUNT, OWNER)); }
    private static void setFile(String value) { SummaryHistoryStorage.FILES.put(SummaryHistoryStorage.key(ACCOUNT, OWNER), value); }
    private static String ids(List<SummaryHistoryStore.Record> records) {
        StringBuilder text = new StringBuilder();
        for (SummaryHistoryStore.Record record : records) { if (text.length() > 0) text.append(','); text.append(record.id); }
        return text.toString();
    }
    private static void reset() {
        SummaryHistoryStorage.reset(); SummaryHistoryCipher.reset();
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER); UserConfig.getInstance(1).setClientUserId(2000);
    }
    private interface Case { void run() throws Exception; }
    private static void test(String name, Case action) throws Exception {
        try { action.run(); passed++; } catch (Throwable failure) { throw new AssertionError(name, failure); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expect(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
