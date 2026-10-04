/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Actual state machine and JCE boundary; no Telegram messages or model requests are sent. */
public final class SummaryPublishStoreTest {
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000, CHAT = -100;
    private static int tests, assertions;
    public static void main(String[] args) throws Exception {
        test("immutable encrypted sending copy leaves generated original unchanged", SummaryPublishStoreTest::roundTrip);
        test("only confirmed positive server IDs enter feedback exclusion", SummaryPublishStoreTest::confirmedOnly);
        test("multi-part partial failure retries only its original native identity", SummaryPublishStoreTest::partialRetry);
        test("wrong scope text local ID and random ID cannot associate a callback", SummaryPublishStoreTest::wrongIdentity);
        test("scheduled acknowledgement is not publication", SummaryPublishStoreTest::scheduled);
        test("abandoned preparation cannot later enter queue", SummaryPublishStoreTest::abandoned);
        test("deleting history prevents stale callbacks from recreating records", SummaryPublishStoreTest::deletedHistory);
        test("owner switch and old-owner cleanup preserve new owner", SummaryPublishStoreTest::ownerIsolation);
        test("namespaces are independently encrypted and cleared", SummaryPublishStoreTest::namespaces);
        test("capacity eviction is bounded and does not change source history", SummaryPublishStoreTest::capacity);
        test("write and ciphertext failures never report a fabricated success", SummaryPublishStoreTest::failures);
        test("invalid preparations cannot create partial sending attempts", SummaryPublishStoreTest::invalid);
        test("native params callbacks preserve order and original retry provenance", SummaryPublishStoreTest::nativeCallbacks);
        System.out.println("SummaryPublishStoreTest: " + tests + " tests, " + assertions + " assertions passed");
    }

    private static void roundTrip() {
        reset();
        ArrayList<String> parts = new ArrayList<>(Arrays.asList("用户修改😀\n", "第二段"));
        SummaryPublishStore.Attempt attempt = prepare(parts);
        parts.clear();
        check(attempt.parts.size() == 2 && attempt.getPartText(0).equals("用户修改😀\n"), "parts were not snapshotted");
        expect(() -> attempt.parts.clear(), "parts must be immutable");
        check(load(attempt).editedSummary.equals("用户修改😀\n第二段"), "edited draft round trip changed text");
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "source").summary.equals("生成原稿"), "sending edit changed original");
        String ciphertext = disk();
        check(ciphertext.startsWith("v1:") && !ciphertext.contains("用户修改"), "sending text stored plaintext");
        String plain = SummaryPrivateStorage.read("publish", ACCOUNT, OWNER, SummaryPublishStore.MAX_STORAGE_BYTES);
        check(!plain.contains("生成原稿") && !plain.contains("sources") && !plain.contains("api_key") && !plain.contains("base_url"), "unneeded source or secret persisted");
        HashMap<String, String> existing = new HashMap<>(); existing.put("unrelated", "keep");
        HashMap<String, String> params = SummaryPublishStore.partParams(attempt, 0, existing);
        check(existing.size() == 1 && params.size() == 4 && params.get("unrelated").equals("keep"), "native params were mutated");
        check(!params.toString().contains("用户修改"), "native params contain draft body");
    }

    private static void confirmedOnly() {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("发送稿"));
        confirm(a, 0, -1, 9001, 501, false);
        check(load(a).status == SummaryPublishStore.Status.PREPARED && confirmed().isEmpty(), "unbound ACK fabricated sent state");
        queue(a, 0, -1, 9001);
        check(load(a).status == SummaryPublishStore.Status.SENDING && confirmed().isEmpty(), "queue or QuickAck counted as sent");
        confirm(a, 0, -1, 9001, 0, false);
        check(confirmed().isEmpty(), "nonpositive server ID accepted");
        confirm(a, 0, -1, 9001, 501, false);
        check(load(a).status == SummaryPublishStore.Status.SENT && load(a).confirmedCount == 1, "real ACK not stored");
        check(confirmed().contains(new SummaryFilter.PublishedMessageId(CHAT, 501)), "server ID missing from exclusion");
        check(SummaryPublishStore.confirmedMessages(ACCOUNT, OWNER, -999).isEmpty(), "server ID leaked to another chat");
        fail(a, 0, -1, 9001); queue(a, 0, -1, 9001);
        check(load(a).status == SummaryPublishStore.Status.SENT, "late failure/retry downgraded confirmed part");
    }

    private static void partialRetry() {
        reset(); SummaryPublishStore.Attempt a = prepare(Arrays.asList("一", "二", "三"));
        for (int i = 0; i < 3; i++) queue(a, i, -10-i, 100+i);
        confirm(a, 0, -10, 100, 11, false); fail(a, 1, -11, 101);
        check(load(a).status == SummaryPublishStore.Status.PARTIAL && load(a).confirmedCount == 1 && load(a).failedCount == 1, "partial state hides failures");
        check(confirmed().size() == 1, "failed or queued parts excluded");
        queue(a, 1, -99, 999);
        check(load(a).parts.get(1).state == SummaryPublishStore.PartState.FAILED, "retry created a fresh native identity");
        queue(a, 1, -11, 101); confirm(a, 1, -11, 101, 12, false); confirm(a, 2, -12, 102, 13, false);
        check(load(a).status == SummaryPublishStore.Status.SENT && confirmed().size() == 3, "native retry could not complete only failed part");
        check(SummaryPublishStore.listForRecord(ACCOUNT, OWNER, "source").size() == 1, "retry duplicated sending attempt");
    }

    private static void wrongIdentity() {
        reset(); SummaryPublishStore.Attempt a = prepare(Arrays.asList("一", "二"));
        SummaryPublishStore.markQueued(ACCOUNT, OWNER, a.id, 0, CHAT, 0, -1, 50, "未确认编辑");
        check(load(a).parts.get(0).localId == 0, "changed body inherited draft provenance");
        SummaryPublishStore.markQueued(ACCOUNT, OWNER, a.id, 0, -999, 0, -1, 50, "一");
        SummaryPublishStore.markQueued(ACCOUNT, OWNER, a.id, 0, CHAT, 55, -1, 50, "一");
        check(load(a).status == SummaryPublishStore.Status.PREPARED, "cross-chat/topic binding accepted");
        queue(a, 0, -1, 50); queue(a, 1, -2, 50);
        check(load(a).parts.get(1).localId == 0, "one random ID bound multiple parts");
        confirm(a, 0, -1, 51, 5, false); confirm(a, 0, -2, 50, 5, false);
        check(confirmed().isEmpty(), "mismatched native identity confirmed");
    }

    private static void scheduled() {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("稍后"));
        queue(a, 0, -1, 10); confirm(a, 0, -1, 10, 20, true);
        check(load(a).status == SummaryPublishStore.Status.SCHEDULED && confirmed().isEmpty(), "scheduled ACK counted as sent");
        confirm(a, 0, -1, 10, 30, false);
        check(confirmed().contains(new SummaryFilter.PublishedMessageId(CHAT, 30)), "actual publication not represented");
    }

    private static void abandoned() {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("取消编辑"));
        SummaryPublishStore.abandonPrepared(ACCOUNT, OWNER, a.id); queue(a, 0, -1, 10);
        check(load(a).status == SummaryPublishStore.Status.INTERRUPTED && confirmed().isEmpty(), "abandoned draft revived");
        SummaryPublishStore.Attempt b = prepare(Collections.singletonList("已入队")); queue(b, 0, -2, 11);
        SummaryPublishStore.abandonPrepared(ACCOUNT, OWNER, b.id);
        check(load(b).status == SummaryPublishStore.Status.SENDING, "abandon cancelled real native message state");
    }

    private static void deletedHistory() {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("待发送")); queue(a, 0, -1, 10);
        SummaryHistoryStore.delete(ACCOUNT, OWNER, "source"); confirm(a, 0, -1, 10, 50, false);
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "source") == null && load(a) == null, "late ACK resurrected deleted history");
        check(confirmed().isEmpty() && disk() == null, "deleted binding or edited copy remained after pruning");
        expect(() -> prepare(Collections.singletonList("新稿")), "deleted history accepted new sending attempt");
    }

    private static void ownerIsolation() {
        reset(); SummaryPublishStore.Attempt old = prepare(Collections.singletonList("旧账号"));
        UserConfig.getInstance(ACCOUNT).setClientUserId(2000);
        expect(() -> queue(old, 0, -1, 10), "old owner bound a new-account message");
        saveHistory(2000, "source", CHAT, 0);
        SummaryPublishStore.Attempt fresh = SummaryPublishStore.prepare(ACCOUNT, 2000, "source", CHAT, 0, Collections.singletonList("新账号"));
        SummaryPublishStore.clearOwner(ACCOUNT, OWNER);
        check(SummaryPublishStore.get(ACCOUNT, 2000, fresh.id) != null, "old owner cleanup deleted new owner's data");
        expect(() -> SummaryPublishStore.get(ACCOUNT, OWNER, old.id), "old owner read new slot");
    }

    private static void namespaces() {
        reset(); prepare(Collections.singletonList("正文"));
        SummaryPrivateStorage.write("saved_prompts", ACCOUNT, OWNER, "my prompt", 5000);
        String prompts = SummaryPrivateStorage.FILES.get(SummaryPrivateStorage.key("saved_prompts", ACCOUNT, OWNER));
        SummaryPrivateStorage.FILES.put(SummaryPrivateStorage.key("publish", ACCOUNT, OWNER), prompts);
        expect(() -> SummaryPublishStore.listForRecord(ACCOUNT, OWNER, "source"), "ciphertext crossed namespace binding");
        SummaryPublishStore.clearOwner(ACCOUNT, OWNER);
        check(SummaryPrivateStorage.read("saved_prompts", ACCOUNT, OWNER, 5000).equals("my prompt"), "clearing publication removed saved requirements");
        check(SummaryHistoryStore.get(ACCOUNT, OWNER, "source") != null, "clearing publication removed history original");
    }

    private static void capacity() {
        reset(); SummaryPublishStore.Attempt first = prepare(Collections.singletonList("第一份"));
        for (int i = 0; i < SummaryPublishStore.MAX_ATTEMPTS; i++) prepare(Collections.singletonList("发送稿" + i));
        check(SummaryPublishStore.listForRecord(ACCOUNT, OWNER, "source").size() == SummaryPublishStore.MAX_ATTEMPTS, "record count bound exceeded");
        check(load(first) == null && SummaryHistoryStore.get(ACCOUNT, OWNER, "source") != null, "eviction affected original history");
        java.util.Map<String, SummaryPublishStore.Attempt> latest = SummaryPublishStore.latestForRecords(ACCOUNT, OWNER, Collections.singleton("source"));
        check(latest.size() == 1 && latest.get("source").editedSummary.equals("发送稿99"), "history page did not select the latest attempt");
        expect(() -> latest.clear(), "history page state map must be immutable");
    }

    private static void failures() {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("保存稿")); String before = disk();
        SummaryPrivateStorage.failWrites = 1;
        expect(() -> queue(a, 0, -1, 10), "failed persistence appeared successful");
        check(disk().equals(before) && load(a).status == SummaryPublishStore.Status.PREPARED, "failed write changed state");
        SummaryPrivateStorage.FILES.put(SummaryPrivateStorage.key("publish", ACCOUNT, OWNER), "v1:corrupt:corrupt");
        expect(() -> load(a), "broken ciphertext was treated as empty history");
        check(disk().equals("v1:corrupt:corrupt"), "read failure silently overwrote records");
    }

    private static void invalid() {
        reset();
        expect(() -> prepare(Collections.emptyList()), "empty parts accepted");
        expect(() -> prepare(Collections.singletonList("bad\ud800")), "invalid Unicode accepted");
        expect(() -> prepare(Collections.singletonList(" \n")), "blank body accepted");
        expect(() -> SummaryPublishStore.prepare(ACCOUNT, OWNER, "source", -999, 0, Collections.singletonList("text")), "other destination accepted");
        saveHistory(OWNER, "topic", CHAT, 42);
        expect(() -> SummaryPublishStore.prepare(ACCOUNT, OWNER, "topic", CHAT, 43, Collections.singletonList("text")), "specific topic rerouted");
        SummaryPublishStore.Attempt topic = SummaryPublishStore.prepare(ACCOUNT, OWNER, "source", CHAT, 42, Collections.singletonList("text"));
        check(topic.topicId == 42, "all-topics record cannot use explicitly selected same-chat topic");
        expect(() -> SummaryPublishStore.partParams(topic, 5, null), "out-of-bounds part params accepted");
    }

    private static void nativeCallbacks() throws Exception {
        reset(); SummaryPublishStore.Attempt a = prepare(Collections.singletonList("实际发送"));
        HashMap<String, String> params = SummaryPublishStore.partParams(a, 0, null);
        SummaryPublishStore.trackQueued(ACCOUNT, params, CHAT, 0, -100, 300, a.getPartText(0));
        SummaryPublishStore.trackConfirmed(ACCOUNT, params, CHAT, 0, -100, 300, 400, false);
        params.clear();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (load(a).status != SummaryPublishStore.Status.SENT && System.nanoTime() < deadline) Thread.sleep(5);
        check(load(a).status == SummaryPublishStore.Status.SENT && confirmed().size() == 1, "native events lost order or did not snapshot params");
    }

    private static void reset() {
        SummaryPrivateStorage.reset(); SummaryHistoryStorage.reset(); SummaryHistoryCipher.reset();
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER); saveHistory(OWNER, "source", CHAT, 0);
    }
    private static void saveHistory(long owner, String id, long chat, long topic) {
        SummaryHistoryStore.save(ACCOUNT, owner, new SummaryHistoryStore.Record(id, chat, topic, 1000,
                "群", "范围", "覆盖", "", "", "", "生成原稿", false, Collections.emptyList(), false));
    }
    private static SummaryPublishStore.Attempt prepare(List<String> parts) { return SummaryPublishStore.prepare(ACCOUNT, OWNER, "source", CHAT, 0, parts); }
    private static SummaryPublishStore.Attempt load(SummaryPublishStore.Attempt a) { return SummaryPublishStore.get(ACCOUNT, OWNER, a.id); }
    private static void queue(SummaryPublishStore.Attempt a, int i, int local, long random) { SummaryPublishStore.markQueued(ACCOUNT, OWNER, a.id, i, a.dialogId, a.topicId, local, random, a.getPartText(i)); }
    private static void confirm(SummaryPublishStore.Attempt a, int i, int local, long random, int server, boolean scheduled) { SummaryPublishStore.markConfirmed(ACCOUNT, OWNER, a.id, i, a.dialogId, a.topicId, local, random, server, scheduled); }
    private static void fail(SummaryPublishStore.Attempt a, int i, int local, long random) { SummaryPublishStore.markFailed(ACCOUNT, OWNER, a.id, i, a.dialogId, a.topicId, local, random); }
    private static java.util.Set<SummaryFilter.PublishedMessageId> confirmed() { return SummaryPublishStore.confirmedMessages(ACCOUNT, OWNER, CHAT); }
    private static String disk() { return SummaryPrivateStorage.FILES.get(SummaryPrivateStorage.key("publish", ACCOUNT, OWNER)); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void expect(Runnable task, String message) { assertions++; try { task.run(); } catch (RuntimeException expected) { return; } throw new AssertionError(message); }
    private interface Test { void run() throws Exception; }
    private static void test(String name, Test task) throws Exception { task.run(); tests++; }
}
