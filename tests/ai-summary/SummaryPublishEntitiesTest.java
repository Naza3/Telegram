/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Run against the compiled application's real TLRPC and SerializedData classes. */
public final class SummaryPublishEntitiesTest {
    private static final String TEXT = "aaaaBBBBcccc";
    private static int tests, assertions;

    public static void main(String[] args) {
        test("plain text and empty entities", SummaryPublishEntitiesTest::plainText);
        test("all supported styles clip without losing payload", SummaryPublishEntitiesTest::styles);
        test("input-user mention retains access hash and nested input peer", SummaryPublishEntitiesTest::inputUsers);
        test("URL and preformatted language survive wire copying", SummaryPublishEntitiesTest::urlAndLanguage);
        test("blockquote collapsed flag and legacy constructor survive", SummaryPublishEntitiesTest::blockquotes);
        test("formatted date keeps every wire flag and timestamp", SummaryPublishEntitiesTest::formattedDate);
        test("custom emoji retains document ID without mutable cache", SummaryPublishEntitiesTest::customEmoji);
        test("all other native entity constructors stay atomic", SummaryPublishEntitiesTest::atomicTypes);
        test("snapshot detects text-identical formatting and payload edits", SummaryPublishEntitiesTest::identity);
        test("snapshot and each materialization are independent", SummaryPublishEntitiesTest::immutability);
        test("malformed ranges and null entries fail entire capture", SummaryPublishEntitiesTest::invalidRanges);
        test("unrecognized truncated and trailing wire payloads fail closed", SummaryPublishEntitiesTest::invalidPayloads);
        test("foreign missing reordered or clipped part entities are rejected", SummaryPublishEntitiesTest::foreignParts);
        System.out.println("SummaryPublishEntitiesTest: " + tests + " tests, " + assertions + " assertions passed");
    }

    private static void plainText() {
        SummaryPublishEntities.Snapshot first = SummaryPublishEntities.capture(TEXT, null);
        SummaryPublishEntities.Snapshot second = SummaryPublishEntities.capture(TEXT, Collections.emptyList());
        check(first.equals(second) && first.hashCode() == second.hashCode(), "null and empty entities differ");
        check(first.fingerprint().equals(second.fingerprint()) && first.fingerprint().length() == 64, "invalid stable fingerprint");
        for (SummaryPublishPlan.Part part : plan(TEXT, first, 4).parts) {
            check(first.materialize(part).isEmpty(), "plain text acquired entities");
        }
        check(plan("", SummaryPublishEntities.capture("", null), 4).parts.isEmpty(), "empty text acquired a part");
        check(!first.equals(SummaryPublishEntities.capture("other", null)), "text omitted from identity");
    }

    private static void styles() {
        TLRPC.MessageEntity[] styles = {
                new TLRPC.TL_messageEntityBold(), new TLRPC.TL_messageEntityItalic(),
                new TLRPC.TL_messageEntityUnderline(), new TLRPC.TL_messageEntityStrike(),
                new TLRPC.TL_messageEntitySpoiler(), new TLRPC.TL_messageEntityCode(),
                new TLRPC.TL_messageEntityPre(), new TLRPC.TL_messageEntityBlockquote(),
                new TLRPC.TL_messageEntityBlockquote_layer180()
        };
        for (TLRPC.MessageEntity style : styles) {
            style.offset = 1; style.length = 10; style.language = "java";
            SummaryPublishEntities.Snapshot snapshot = capture(style);
            check(!snapshot.ranges().get(0).atomic, "style unexpectedly atomic");
            SummaryPublishPlan plan = plan(TEXT, snapshot, 4);
            int total = 0;
            for (SummaryPublishPlan.Part part : plan.parts) {
                TLRPC.MessageEntity restored = snapshot.materialize(part).get(0);
                check(restored.getClass() == style.getClass(), "style constructor changed");
                check(restored.offset == Math.max(style.offset, part.start) - part.start, "style offset incorrect");
                check(restored.length == Math.min(style.offset + style.length, part.end)
                        - Math.max(style.offset, part.start), "style length incorrect");
                checkSamePayload(style, restored);
                total += restored.length;
            }
            check(total == 10, "style lost covered characters");
        }
    }

    private static void inputUsers() {
        TLRPC.TL_inputMessageEntityMentionName mention = at(new TLRPC.TL_inputMessageEntityMentionName(), 4, 4);
        TLRPC.TL_inputUser user = new TLRPC.TL_inputUser();
        user.user_id = 0x123456789L; user.access_hash = -90324012492833L; mention.user_id = user;
        TLRPC.TL_inputMessageEntityMentionName clone = (TLRPC.TL_inputMessageEntityMentionName) shifted(mention);
        check(clone.user_id != user && clone.user_id.getClass() == user.getClass(), "input user not independently cloned");
        check(clone.user_id.user_id == user.user_id && clone.user_id.access_hash == user.access_hash, "input user fields lost");
        TLRPC.TL_inputUserFromMessage nested = new TLRPC.TL_inputUserFromMessage();
        TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
        peer.channel_id = 0x234567890L; peer.access_hash = 9000123456L;
        nested.peer = peer; nested.msg_id = 321; nested.user_id = 0x345678901L; mention.user_id = nested;
        TLRPC.TL_inputUserFromMessage restored = (TLRPC.TL_inputUserFromMessage)
                ((TLRPC.TL_inputMessageEntityMentionName) shifted(mention)).user_id;
        check(restored.peer != peer && restored.peer.channel_id == peer.channel_id
                && restored.peer.access_hash == peer.access_hash, "nested peer fields lost");
        check(restored.msg_id == nested.msg_id && restored.user_id == nested.user_id, "nested input user fields lost");
        mention.user_id = new TLRPC.TL_inputUserSelf();
        check(((TLRPC.TL_inputMessageEntityMentionName) shifted(mention)).user_id instanceof TLRPC.TL_inputUserSelf,
                "self input user constructor lost");
    }

    private static void urlAndLanguage() {
        TLRPC.TL_messageEntityTextUrl url = at(new TLRPC.TL_messageEntityTextUrl(), 4, 4);
        url.url = "https://example.test/路径?q=1&x=😀";
        check(shifted(url).url.equals(url.url), "URL payload changed");
        TLRPC.TL_messageEntityPre pre = at(new TLRPC.TL_messageEntityPre(), 1, 10);
        pre.language = "language: λ/中文";
        SummaryPublishEntities.Snapshot snapshot = capture(pre);
        for (SummaryPublishPlan.Part part : plan(TEXT, snapshot, 4).parts) {
            check(snapshot.materialize(part).get(0).language.equals(pre.language), "pre language lost on clipped part");
        }
    }

    private static void blockquotes() {
        TLRPC.TL_messageEntityBlockquote quote = at(new TLRPC.TL_messageEntityBlockquote(), 1, 10);
        quote.flags = 0x4000; quote.collapsed = true;
        SummaryPublishEntities.Snapshot snapshot = capture(quote);
        check(quote.flags == 0x4000, "capture mutated native flag field");
        for (SummaryPublishPlan.Part part : plan(TEXT, snapshot, 4).parts) {
            TLRPC.MessageEntity clone = snapshot.materialize(part).get(0);
            check(clone.collapsed && clone.flags == 0x4001, "blockquote flags lost");
        }
        TLRPC.TL_messageEntityBlockquote_layer180 legacy = at(new TLRPC.TL_messageEntityBlockquote_layer180(), 0, 12);
        SummaryPublishEntities.Snapshot old = capture(legacy);
        check(old.materialize(plan(TEXT, old, 20).parts.get(0)).get(0).getClass() == legacy.getClass(),
                "legacy quote wire constructor upgraded");
    }

    private static void formattedDate() {
        TLRPC.TL_messageEntityFormattedDate date = at(new TLRPC.TL_messageEntityFormattedDate(), 4, 4);
        date.flags = 0x4000; date.relative = true; date.short_time = true; date.long_time = true;
        date.short_date = true; date.long_date = true; date.day_of_week = true; date.date = 1791043200;
        TLRPC.TL_messageEntityFormattedDate clone = (TLRPC.TL_messageEntityFormattedDate) shifted(date);
        check(date.flags == 0x4000, "date flags changed on input");
        check(clone.flags == 0x403f && clone.date == date.date, "date wire payload truncated");
        check(clone.relative && clone.short_time && clone.long_time && clone.short_date && clone.long_date
                && clone.day_of_week, "date boolean flags lost");
    }

    private static void customEmoji() {
        TLRPC.TL_messageEntityCustomEmoji emoji = at(new TLRPC.TL_messageEntityCustomEmoji(), 4, 4);
        emoji.document_id = 0x123456789abcdefL; emoji.document = new TLRPC.TL_documentEmpty();
        emoji.document.id = emoji.document_id;
        TLRPC.TL_messageEntityCustomEmoji clone = (TLRPC.TL_messageEntityCustomEmoji) shifted(emoji);
        check(clone.document_id == emoji.document_id, "emoji ID lost");
        check(clone.document == null, "mutable non-wire document cache retained");
    }

    private static void atomicTypes() {
        TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName(); mention.user_id = 12345678901L;
        TLRPC.TL_messageEntityMentionName_layer131 legacy = new TLRPC.TL_messageEntityMentionName_layer131(); legacy.user_id = 123;
        TLRPC.TL_messageEntityDiffReplace replace = new TLRPC.TL_messageEntityDiffReplace(); replace.old_text = "original text";
        TLRPC.MessageEntity[] atoms = {new TLRPC.TL_messageEntityBotCommand(), new TLRPC.TL_messageEntityEmail(),
                new TLRPC.TL_messageEntityUnknown(), new TLRPC.TL_messageEntityUrl(), new TLRPC.TL_messageEntityMention(),
                new TLRPC.TL_messageEntityCashtag(), new TLRPC.TL_messageEntityHashtag(), new TLRPC.TL_messageEntityBankCard(),
                new TLRPC.TL_messageEntityPhone(), mention, legacy, new TLRPC.TL_messageEntityDiffInsert(),
                replace, new TLRPC.TL_messageEntityDiffDelete()};
        for (TLRPC.MessageEntity atom : atoms) {
            at(atom, 4, 4);
            check(capture(atom).ranges().get(0).atomic, "non-style entity classified as splittable");
            TLRPC.MessageEntity clone = shifted(atom);
            checkSamePayload(atom, clone);
            expect(() -> plan(TEXT, capture(atom), 3), "oversized atomic entity was split");
        }
    }

    private static void identity() {
        TLRPC.TL_messageEntityBold bold = at(new TLRPC.TL_messageEntityBold(), 4, 4);
        SummaryPublishEntities.Snapshot old = capture(bold);
        check(old.equals(capture(bold)), "identical native payload did not compare equal");
        different(old, capture(at(new TLRPC.TL_messageEntityItalic(), 4, 4)));
        bold.offset = 3; different(old, capture(bold));
        TLRPC.TL_messageEntityTextUrl link = at(new TLRPC.TL_messageEntityTextUrl(), 4, 4); link.url = "https://a.test";
        old = capture(link); link.url = "https://b.test"; different(old, capture(link));
        TLRPC.TL_messageEntityPre pre = at(new TLRPC.TL_messageEntityPre(), 4, 4); pre.language = "java";
        old = capture(pre); pre.language = "kotlin"; different(old, capture(pre));
        TLRPC.TL_messageEntityFormattedDate date = at(new TLRPC.TL_messageEntityFormattedDate(), 4, 4); date.date = 123;
        old = capture(date); date.date = 124; different(old, capture(date));
        old = capture(date); date.day_of_week = true; different(old, capture(date));
        TLRPC.TL_messageEntityCustomEmoji emoji = at(new TLRPC.TL_messageEntityCustomEmoji(), 4, 4); emoji.document_id = 123;
        old = capture(emoji); emoji.document_id = 124; different(old, capture(emoji));
        TLRPC.TL_inputMessageEntityMentionName mention = at(new TLRPC.TL_inputMessageEntityMentionName(), 4, 4);
        mention.user_id = new TLRPC.TL_inputUser(); mention.user_id.access_hash = 123;
        old = capture(mention); mention.user_id.access_hash = 124; different(old, capture(mention));
        different(SummaryPublishEntities.capture(TEXT, Arrays.asList(bold, link)),
                SummaryPublishEntities.capture(TEXT, Arrays.asList(link, bold)));
    }

    private static void immutability() {
        TLRPC.TL_inputMessageEntityMentionName mention = at(new TLRPC.TL_inputMessageEntityMentionName(), 4, 4);
        mention.user_id = new TLRPC.TL_inputUser(); mention.user_id.user_id = 100; mention.user_id.access_hash = 200;
        ArrayList<TLRPC.MessageEntity> originals = new ArrayList<>(Collections.singletonList(mention));
        SummaryPublishEntities.Snapshot snapshot = SummaryPublishEntities.capture(TEXT, originals);
        String fingerprint = snapshot.fingerprint();
        SummaryPublishPlan.Part part = plan(TEXT, snapshot, 20).parts.get(0);
        originals.clear(); mention.offset = 0; mention.length = 1; mention.user_id.access_hash = 300;
        ArrayList<TLRPC.MessageEntity> first = snapshot.materialize(part);
        TLRPC.TL_inputMessageEntityMentionName restored = (TLRPC.TL_inputMessageEntityMentionName) first.get(0);
        check(restored.offset == 4 && restored.length == 4 && restored.user_id.access_hash == 200, "input mutation reached snapshot");
        restored.offset = 0; restored.user_id.access_hash = 400; first.clear();
        restored = (TLRPC.TL_inputMessageEntityMentionName) snapshot.materialize(part).get(0);
        check(restored.offset == 4 && restored.user_id.access_hash == 200, "materialized object was shared");
        check(snapshot.fingerprint().equals(fingerprint), "immutable fingerprint changed");
        expect(() -> snapshot.ranges().clear(), "ranges are mutable");
    }

    private static void invalidRanges() {
        expect(() -> SummaryPublishEntities.capture(null, null), "null text accepted");
        expect(() -> SummaryPublishEntities.capture(TEXT, Collections.singletonList(null)), "null entity accepted");
        int[][] invalid = {{-1, 1}, {0, 0}, {0, -1}, {12, 1}, {1, Integer.MAX_VALUE}, {Integer.MAX_VALUE, 1}};
        for (int[] range : invalid) {
            expect(() -> capture(at(new TLRPC.TL_messageEntityBold(), range[0], range[1])), "invalid range accepted");
        }
        expect(() -> SummaryPublishEntities.capture(TEXT, Arrays.asList(at(new TLRPC.TL_messageEntityBold(), 0, 4),
                at(new TLRPC.TL_messageEntityItalic(), 12, 1))), "invalid later entity produced partial snapshot");
    }

    private static void invalidPayloads() {
        TLRPC.MessageEntity unknown = at(new TLRPC.TL_messageEntityBold() {
            @Override public void serializeToStream(OutputSerializedData output) { output.writeInt32(0x01234567); }
        }, 4, 4);
        expect(() -> capture(unknown), "unrecognized constructor was discarded");
        TLRPC.MessageEntity truncated = at(new TLRPC.TL_messageEntityBold() {
            @Override public void serializeToStream(OutputSerializedData output) { output.writeInt32(constructor); }
        }, 4, 4);
        expect(() -> capture(truncated), "truncated payload accepted");
        TLRPC.MessageEntity trailing = at(new TLRPC.TL_messageEntityBold() {
            @Override public void serializeToStream(OutputSerializedData output) {
                super.serializeToStream(output); output.writeInt32(123);
            }
        }, 4, 4);
        expect(() -> capture(trailing), "unconsumed trailing payload accepted");
        TLRPC.TL_inputMessageEntityMentionName missingUser = at(new TLRPC.TL_inputMessageEntityMentionName(), 4, 4);
        expect(() -> capture(missingUser), "missing nested input user accepted");
    }

    private static void foreignParts() {
        TLRPC.TL_messageEntityBold bold = at(new TLRPC.TL_messageEntityBold(), 1, 10);
        TLRPC.TL_messageEntityItalic italic = at(new TLRPC.TL_messageEntityItalic(), 2, 8);
        SummaryPublishEntities.Snapshot snapshot = SummaryPublishEntities.capture(TEXT, Arrays.asList(bold, italic));
        expect(() -> snapshot.materialize(null), "null part accepted");
        expect(() -> snapshot.materialize(SummaryPublishPlan.create("XXXXXXXXXXXX", snapshot.ranges(), 20, 64).parts.get(0)),
                "different text accepted");
        expect(() -> snapshot.materialize(SummaryPublishPlan.create(TEXT, Collections.emptyList(), 20, 64).parts.get(0)),
                "omitted entities accepted");
        ArrayList<SummaryPublishPlan.EntityRange> reversed = new ArrayList<>(snapshot.ranges()); Collections.reverse(reversed);
        expect(() -> snapshot.materialize(SummaryPublishPlan.create(TEXT, reversed, 20, 64).parts.get(0)),
                "reordered source indices accepted");
        List<SummaryPublishPlan.EntityRange> changed = Arrays.asList(new SummaryPublishPlan.EntityRange(0, 2, 11, false),
                snapshot.ranges().get(1));
        expect(() -> snapshot.materialize(SummaryPublishPlan.create(TEXT, changed, 20, 64).parts.get(0)), "wrong clipped range accepted");
        SummaryPublishEntities.Snapshot plain = SummaryPublishEntities.capture(TEXT, null);
        expect(() -> plain.materialize(plan(TEXT, snapshot, 20).parts.get(0)), "unrelated entities accepted");
        TLRPC.TL_messageEntityUrl url = at(new TLRPC.TL_messageEntityUrl(), 4, 4);
        SummaryPublishEntities.Snapshot atomic = capture(url);
        SummaryPublishPlan unsafe = SummaryPublishPlan.create(TEXT,
                Collections.singletonList(new SummaryPublishPlan.EntityRange(0, 4, 8, false)), 6, 64);
        expect(() -> atomic.materialize(unsafe.parts.get(0)), "atomic entity was clipped by foreign plan");
    }

    private static TLRPC.MessageEntity shifted(TLRPC.MessageEntity entity) {
        SummaryPublishEntities.Snapshot snapshot = capture(entity);
        check(snapshot.ranges().get(0).atomic, "expected atomic entity");
        for (SummaryPublishPlan.Part part : plan(TEXT, snapshot, 6).parts) {
            ArrayList<TLRPC.MessageEntity> entities = snapshot.materialize(part);
            if (!entities.isEmpty()) {
                TLRPC.MessageEntity clone = entities.get(0);
                check(clone.offset == 0 && clone.length == 4, "atomic entity did not rebase losslessly");
                checkSamePayload(entity, clone);
                return clone;
            }
        }
        throw new AssertionError("atomic entity vanished");
    }

    private static SummaryPublishEntities.Snapshot capture(TLRPC.MessageEntity entity) {
        return SummaryPublishEntities.capture(TEXT, Collections.singletonList(entity));
    }

    private static SummaryPublishPlan plan(String text, SummaryPublishEntities.Snapshot snapshot, int max) {
        return SummaryPublishPlan.create(text, snapshot.ranges(), max, 64);
    }

    private static <T extends TLRPC.MessageEntity> T at(T entity, int offset, int length) {
        entity.offset = offset; entity.length = length; return entity;
    }

    private static void checkSamePayload(TLRPC.MessageEntity original, TLRPC.MessageEntity clone) {
        int offset = clone.offset, length = clone.length;
        clone.offset = original.offset; clone.length = original.length;
        check(Arrays.equals(wire(original), wire(clone)), "native payload changed beyond offset and length");
        clone.offset = offset; clone.length = length;
    }

    private static byte[] wire(TLRPC.MessageEntity entity) {
        SerializedData output = new SerializedData(); int flags = entity.flags;
        try { entity.serializeToStream(output); return output.toByteArray(); }
        finally { entity.flags = flags; output.cleanup(); }
    }

    private static void different(SummaryPublishEntities.Snapshot old, SummaryPublishEntities.Snapshot changed) {
        check(!old.equals(changed), "changed entity snapshot compared equal");
        check(!old.fingerprint().equals(changed.fingerprint()), "changed entity fingerprint compared equal");
    }

    private static void test(String name, Runnable run) {
        try { run.run(); tests++; }
        catch (Throwable error) { throw new AssertionError(name, error); }
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }

    private static void expect(Runnable action, String message) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError(message);
    }
}
