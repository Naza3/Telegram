/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.messenger.UserConfig;
import java.util.List;

public final class SavedSummaryPromptsTest {
    private static int assertions;
    private static final long OWNER = 1000;
    public static void main(String[] args) {
        emptyAndRoundTrip(); editsAndDeletion(); validation(); capacity(); ownersAndStorage();
        System.out.println("SavedSummaryPromptsTest: 5 cases passed, " + assertions + " assertions");
    }
    private static void emptyAndRoundTrip() {
        reset();
        check(all().isEmpty(), "new owner inherited built-in requirements");
        String body = "  关注 🧑🏽‍💻 的问题\n保留不确定性；输出格式由我决定。  ";
        SavedSummaryPrompts.Entry created = SavedSummaryPrompts.create(0, OWNER, "  我的规则 🧭  ", body);
        check(created.name.equals("我的规则 🧭") && created.text.equals(body), "user text was silently augmented or changed");
        check(all().size() == 1 && all().get(0).text.equals(body), "round trip changed Unicode or lines");
        check(all().get(0).id.equals(created.id) && all().get(0).revision == 1, "stable identity lost");
        expect(() -> all().clear());
        String disk = SummaryPrivateStorage.FILES.get(SummaryPrivateStorage.key("saved_prompts", 0, OWNER));
        check(disk != null && !disk.contains("关注") && !disk.contains("我的规则"), "instructions written in plaintext");
    }
    private static void editsAndDeletion() {
        reset();
        SavedSummaryPrompts.Entry first = SavedSummaryPrompts.create(0, OWNER, "one", "自己填写的要求");
        SavedSummaryPrompts.Entry other = SavedSummaryPrompts.create(0, OWNER, "two", "另一条");
        SavedSummaryPrompts.Entry updated = SavedSummaryPrompts.update(0, OWNER, first.id, first.revision, "renamed", "改好的要求");
        check(all().size() == 2 && all().get(0).id.equals(first.id), "editing created a duplicate or lost identity");
        check(updated.revision == 2 && updated.text.equals("改好的要求"), "edit not persisted");
        expect(() -> SavedSummaryPrompts.update(0, OWNER, first.id, first.revision, "old", "过时编辑"));
        check(all().get(0).text.equals("改好的要求"), "stale edit overwrote new content");
        SavedSummaryPrompts.delete(0, OWNER, first.id);
        expect(() -> SavedSummaryPrompts.update(0, OWNER, first.id, updated.revision, "reborn", "旧页面"));
        check(all().size() == 1 && all().get(0).id.equals(other.id), "deleted entry was resurrected");
        SavedSummaryPrompts.delete(0, OWNER, "absent");
        check(all().size() == 1, "unknown deletion changed other entries");
    }
    private static void validation() {
        reset();
        SavedSummaryPrompts.create(0, OWNER, "ＡＢＣ", "个人要求");
        expect(() -> SavedSummaryPrompts.create(0, OWNER, "abc", "重复名称"));
        for (String bad : new String[]{"", "  ", "a\nb", "a\u0000b", "\ud800", "\udc00", "😀".repeat(81)}) {
            expect(() -> SavedSummaryPrompts.create(0, OWNER, bad, "要求"));
        }
        for (String bad : new String[]{"", "  ", "\ud800", "\udc00", "😀".repeat(1001)}) {
            expect(() -> SavedSummaryPrompts.create(0, OWNER, "valid", bad));
        }
        SavedSummaryPrompts.Entry edge = SavedSummaryPrompts.create(0, OWNER, "😀".repeat(80), "🧭".repeat(1000));
        check(edge.name.codePointCount(0, edge.name.length()) == 80
                && edge.text.codePointCount(0, edge.text.length()) == 1000, "Unicode limits counted UTF-16 halves");
        check(all().size() == 2, "rejected validation mutated library");
    }
    private static void capacity() {
        reset();
        for (int i = 0; i < SavedSummaryPrompts.MAX_PROMPTS; i++) SavedSummaryPrompts.create(0, OWNER, "规则" + i, "🧭".repeat(1000));
        String oldest = all().get(all().size() - 1).id;
        expect(() -> SavedSummaryPrompts.create(0, OWNER, "第51条", "要求"));
        check(all().size() == 50 && all().get(49).id.equals(oldest), "capacity silently evicted user content");
        SavedSummaryPrompts.Entry last = all().get(49);
        SavedSummaryPrompts.update(0, OWNER, last.id, last.revision, "容量满仍可编辑", "短要求");
        check(all().size() == 50 && all().get(0).name.equals("容量满仍可编辑"), "full capacity blocked edit");
    }
    private static void ownersAndStorage() {
        reset();
        SavedSummaryPrompts.create(0, OWNER, "secret", "私有要求");
        UserConfig.getInstance(0).setClientUserId(2000);
        expect(() -> all());
        expect(() -> SavedSummaryPrompts.create(0, OWNER, "stale", "旧账号"));
        check(SavedSummaryPrompts.list(0, 2000).isEmpty(), "new owner saw old slot content");
        SavedSummaryPrompts.create(0, 2000, "new", "新账号内容");
        SummaryPrivateStorage.write("publish", 0, OWNER, "unrelated", 1000);
        SavedSummaryPrompts.clearOwner(0, OWNER);
        check(SavedSummaryPrompts.list(0, 2000).size() == 1, "old-owner cleanup erased new owner");
        check(SummaryPrivateStorage.read("publish", 0, OWNER, 1000).equals("unrelated"), "prompt cleanup erased publishing data");
        UserConfig.getInstance(0).setClientUserId(OWNER);
        check(all().isEmpty(), "logout retained prompts");
        SavedSummaryPrompts.Entry entry = SavedSummaryPrompts.create(0, OWNER, "keep", "保留的文字");
        SummaryPrivateStorage.failWrites = 1;
        expect(() -> SavedSummaryPrompts.update(0, OWNER, entry.id, entry.revision, "broken", "失败保存"));
        check(all().get(0).text.equals("保留的文字"), "failed write damaged prior content");
        SummaryPrivateStorage.write("saved_prompts", 0, OWNER, "{\"version\":999,\"entries\":[]}", 1000);
        expect(() -> all()); expect(() -> SavedSummaryPrompts.create(0, OWNER, "new", "覆盖损坏数据"));
        check(SummaryPrivateStorage.read("saved_prompts", 0, OWNER, 1000).contains("999"), "corrupt file silently replaced");
    }
    private static List<SavedSummaryPrompts.Entry> all() { return SavedSummaryPrompts.list(0, OWNER); }
    private static void reset() { SummaryPrivateStorage.reset(); UserConfig.getInstance(0).setClientUserId(OWNER); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void expect(Runnable run) {
        assertions++; try { run.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("expected rejection");
    }
}
