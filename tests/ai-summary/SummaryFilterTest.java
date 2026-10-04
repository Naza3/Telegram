package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SummaryFilterTest {
    private static final long OWNER = 1000;
    private static final long DIALOG = -100;
    private static int assertions;

    public static void main(String[] args) {
        modesAndIdentity();
        keywordsAndMembers();
        boundedContext();
        combinationsAndEmpty();
        immutableOptionsAndSnapshot();
        publishedMessageIdentity();
        publishedExclusionUsesOnlyConfirmedIds();
        publishedExclusionPreservesOccurrences();
        publishedExclusionIsAnImmutableSnapshot();
        publishedExclusionBeforeSelfContext();
        publishedExclusionRejectsInvalidInputs();
        excludedUserIdentityAndCounts();
        excludedUsersNeverReappearAsContext();
        excludedOptionsAreImmutable();
        System.out.println("SummaryFilterTest: " + assertions + " assertions passed");
    }

    private static void modesAndIdentity() {
        List<SummaryMessage> sources = Arrays.asList(message(1, 55, "unrelated"),
                message(2, OWNER, "own message"), message(3, 55, "same display name"));
        SummaryFilter.Result all = SummaryFilter.apply(sources, OWNER, SummaryFilter.Options.DEFAULT);
        check(ids(all).equals("1,2,3") && all.matchedCount == 3 && all.contextCount == 0 && !all.filtered,
                "default must preserve the full input");
        SummaryFilter.Result focus = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FOCUS_SELF, 0, ""));
        check(ids(focus).equals("1,2,3") && !focus.filtered, "focus mode secretly excluded messages");
        check(focus.coverageNote.contains("保留全部"), "focus coverage does not disclose full input");
        SummaryFilter.Result self = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(self.filtered && self.matchedCount == 1 && self.contextCount == 2,
                "same display names confused sender identity");
        check(self.coverageNote.contains("本人身份可确认的发言"), "own-message inclusion is undisclosed");

        SummaryMessage unknownReply = full(10, 55, "unknown reply", false, 99, DIALOG, false, false);
        SummaryMessage knownReply = full(20, 55, "confirmed reply", false, 99, DIALOG, true, true);
        SummaryMessage mention = full(30, 55, "explicit mention", true, 0, 0, false, false);
        SummaryFilter.Result relation = SummaryFilter.apply(Arrays.asList(unknownReply, knownReply, mention), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(relation.matchedCount == 2 && relation.contextCount == 1, "unknown reply counted as an explicit self relation");
        SummaryMessage outgoingAnonymous = new SummaryMessage(DIALOG, 1, 1, "Same name", "anonymous",
                -300, 0, 0, false, true, 0, false, false);
        SummaryFilter.Result anonymous = SummaryFilter.apply(Arrays.asList(outgoingAnonymous), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(anonymous.messages.isEmpty(), "anonymous sender was inferred from outgoing/display name");
    }

    private static void keywordsAndMembers() {
        List<SummaryMessage> sources = Arrays.asList(message(1, 55, "版本 RELEASE A.B"),
                message(2, 77, "版本 release axb"), message(3, 55, "another topic"));
        SummaryFilter.Result chinese = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, "  版本  "));
        check(ids(chinese).equals("1,2") && chinese.contextCount == 0 && chinese.filtered, "Chinese literal keyword failed");
        SummaryFilter.Result member = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, 55, "release"));
        check(ids(member).equals("1") && member.matchedCount == 1, "member and keyword must combine with AND");
        SummaryFilter.Result literal = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, "a.b"));
        check(ids(literal).equals("1"), "keyword was interpreted as a regex");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            SummaryFilter.Result rootLocale = SummaryFilter.apply(Arrays.asList(message(1, 55, "FIX ISSUE")), OWNER,
                    new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, "fix issue"));
            check(rootLocale.matchedCount == 1, "keyword matching depended on device locale");
        } finally { Locale.setDefault(previous); }
        SummaryFilter.Result signedPeer = SummaryFilter.apply(Arrays.asList(message(1, -400, "anonymous")), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, -400, ""));
        check(signedPeer.matchedCount == 1 && signedPeer.filtered, "negative Telegram sender IDs lost identity");
    }

    private static void boundedContext() {
        ArrayList<SummaryMessage> sources = new ArrayList<>();
        for (int id = 1; id <= 7; id++) sources.add(message(id, 55, "ordinary " + id));
        sources.set(4, full(5, 55, "explicit self reply", false, 1, DIALOG, true, true));
        sources.add(sources.get(4)); // Duplicate identity must not duplicate a match or context.
        SummaryFilter.Result result = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(ids(result).equals("1,4,5,6"), "context must be parent and at most one source per side in original order");
        check(result.matchedCount == 1 && result.contextCount == 3, "overlapping/duplicate sources inflated counts");
        check(result.coverageNote.contains("上下文可不满足筛选条件"), "context selection not disclosed");

        SummaryMessage foreignParent = new SummaryMessage(-200, 1, 1, "Other group", "not this parent");
        SummaryMessage matched = full(5, 55, "mention", true, 1, -200, false, false);
        SummaryFilter.Result foreign = SummaryFilter.apply(Arrays.asList(foreignParent, matched), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(foreign.messages.size() == 1 && foreign.messages.get(0).dialogId == DIALOG,
                "context crossed a dialog boundary");

        // Context does not recursively expand its own parent or adjacent neighbors.
        SummaryMessage nestedParent = full(4, 55, "context has a parent too", false, 2, DIALOG, false, false);
        sources.set(3, nestedParent);
        SummaryFilter.Result nested = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(ids(nested).equals("1,4,5,6"), "context recursively expanded beyond the bounded snapshot selection");
    }

    private static void combinationsAndEmpty() {
        SummaryMessage match = full(2, 55, "Release confirmed", true, 0, 0, false, false);
        List<SummaryMessage> sources = Arrays.asList(message(1, 77, "context only"), match,
                full(3, 77, "Release by another member", true, 0, 0, false, false));
        SummaryFilter.Result combined = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 55, "RELEASE"));
        check(combined.matchedCount == 1 && combined.contextCount == 2 && ids(combined).equals("1,2,3"),
                "self/member/keyword AND selection or disclosed context incorrect");
        SummaryFilter.Result focusMember = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FOCUS_SELF, 55, "release"));
        check(ids(focusMember).equals("2") && focusMember.filtered && focusMember.contextCount == 0,
                "focus mode overrode a real member/keyword filter");
        SummaryFilter.Result empty = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 55, "absent"));
        check(empty.messages.isEmpty() && empty.matchedCount == 0 && empty.contextCount == 0 && empty.filtered,
                "no match silently fell back to all messages");
        SummaryFilter.Result allHappenToMatch = SummaryFilter.apply(Arrays.asList(match), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, 55, ""));
        check(allHappenToMatch.filtered, "selected filter must prohibit cursor advancement even when all inputs match");
        SummaryFilter.Result noSources = SummaryFilter.apply(new ArrayList<>(), OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, ""));
        check(noSources.messages.isEmpty() && noSources.filtered, "empty filtered range became unfiltered");
    }

    private static void immutableOptionsAndSnapshot() {
        ArrayList<SummaryMessage> input = new ArrayList<>(); input.add(message(1, 55, "input"));
        SummaryFilter.Options options = new SummaryFilter.Options(null, 0, "   ");
        SummaryFilter.Result result = SummaryFilter.apply(input, OWNER, options);
        input.clear();
        check(result.messages.size() == 1 && options.mode == SummaryFilter.Mode.ALL && options.keyword.isEmpty(),
                "caller list mutation changed result snapshot or option normalization");
        result.messages.clear();
        check(input.isEmpty(), "result collection was not copied");
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 128; i++) emoji.append("😀");
        SummaryFilter.Options unicode = new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, emoji.toString());
        check(unicode.keyword.codePointCount(0, unicode.keyword.length()) == 128, "Unicode limit counted UTF-16 halves");
        boolean longRejected = false;
        try { new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, emoji + "x"); }
        catch (IllegalArgumentException expected) { longRejected = true; }
        check(longRejected, "over-limit keyword accepted");
        boolean unknownOwner = false;
        try { SummaryFilter.apply(Arrays.asList(message(1, 0, "unknown sender")), 0,
                new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, "")); }
        catch (IllegalArgumentException expected) { unknownOwner = true; }
        check(unknownOwner, "unknown account was matched against unknown sender ID zero");
        boolean nullSource = false;
        try { SummaryFilter.apply(Arrays.asList((SummaryMessage) null), OWNER, null); }
        catch (IllegalArgumentException expected) { nullSource = true; }
        check(nullSource, "invalid source was silently discarded");
    }

    private static void publishedMessageIdentity() {
        SummaryFilter.PublishedMessageId identity = new SummaryFilter.PublishedMessageId(DIALOG, 7);
        SummaryFilter.PublishedMessageId same = new SummaryFilter.PublishedMessageId(DIALOG, 7);
        check(identity.dialogId == DIALOG && identity.messageId == 7, "published identity lost its exact dialog/message fields");
        check(identity.equals(same) && same.equals(identity) && identity.hashCode() == same.hashCode(),
                "equal published identities must share value equality and hash code");
        Set<SummaryFilter.PublishedMessageId> identities = new HashSet<>(Arrays.asList(identity, same));
        check(identities.size() == 1 && identities.contains(new SummaryFilter.PublishedMessageId(DIALOG, 7)),
                "confirmed identity sets must resolve separately constructed equal values");
        check(!identity.equals(new SummaryFilter.PublishedMessageId(-200, 7))
                && !identity.equals(new SummaryFilter.PublishedMessageId(DIALOG, 8))
                && !identity.equals(null) && !identity.equals("-100:7"), "published identity equality ignored its full type or composite key");
        SummaryFilter.PublishedMessageId limits = new SummaryFilter.PublishedMessageId(Long.MIN_VALUE + 1, Integer.MAX_VALUE);
        check(limits.dialogId == Long.MIN_VALUE + 1 && limits.messageId == Integer.MAX_VALUE, "valid signed identity limits were narrowed");
        for (long dialog : new long[] {0, 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            invalid(() -> new SummaryFilter.PublishedMessageId(dialog, 1), "invalid published dialog was accepted");
        }
        for (int id : new int[] {0, -1, Integer.MIN_VALUE}) {
            invalid(() -> new SummaryFilter.PublishedMessageId(DIALOG, id), "unconfirmed/local message ID was accepted");
        }
    }

    private static void publishedExclusionUsesOnlyConfirmedIds() {
        String summaryLike = "【话题】发布安排\n【结论】确认上线\n【待办】完成检查😀";
        SummaryMessage published = message(10, OWNER, summaryLike);
        SummaryMessage foreignSameId = new SummaryMessage(-200, 10, 10, "Same name", summaryLike,
                OWNER, 0, 0, false, true, 0, false, false);
        SummaryMessage ordinaryOwn = message(11, OWNER, summaryLike);
        SummaryMessage listedOtherSender = message(12, 55, "没有总结标题，也不是本人发言");
        SummaryMessage pendingOrOtherDevice = message(13, OWNER, "【话题】未确认或其他客户端的总结");
        SummaryMessage rich = new SummaryMessage(DIALOG, 14, 123, "保留姓名", "保留\n完整原文😀", -300,
                10, DIALOG, true, false, 456, true, true, 77, "服务端引用原文");
        List<SummaryMessage> input = Arrays.asList(published, foreignSameId, ordinaryOwn,
                listedOtherSender, pendingOrOtherDevice, rich);
        Set<SummaryFilter.PublishedMessageId> confirmed = new HashSet<>(Arrays.asList(
                new SummaryFilter.PublishedMessageId(DIALOG, 10), new SummaryFilter.PublishedMessageId(DIALOG, 12),
                new SummaryFilter.PublishedMessageId(DIALOG, 99)));
        SummaryFilter.ExclusionResult result = SummaryFilter.excludePublished(input, confirmed);
        check(result.messages.equals(Arrays.asList(foreignSameId, ordinaryOwn, pendingOrOtherDevice, rich))
                && result.excludedCount == 2, "only exact confirmed dialog/message pairs may remove actual input entries");
        check(result.messages.get(3) == rich && rich.text.equals("保留\n完整原文😀") && rich.quoteText.equals("服务端引用原文"),
                "remaining sources must retain original objects, Unicode text and all metadata");
        check(input.size() == 6 && input.get(0) == published && confirmed.size() == 3,
                "exclusion mutated the loaded range or caller's confirmed-ID set");
        SummaryFilter.ExclusionResult noMatches = SummaryFilter.excludePublished(input,
                Collections.singleton(new SummaryFilter.PublishedMessageId(-300, 10)));
        check(noMatches.excludedCount == 0 && noMatches.messages.equals(input),
                "unrelated confirmed IDs must not infer exclusions from titles, owner, names or matching numeric IDs");
    }

    private static void publishedExclusionPreservesOccurrences() {
        SummaryMessage first = message(1, 55, "first");
        SummaryMessage repeated = message(2, OWNER, "published");
        SummaryMessage duplicateIdentity = message(2, 55, "same identity with different source text");
        SummaryMessage last = message(3, 55, "last");
        List<SummaryMessage> input = Arrays.asList(first, repeated, first, duplicateIdentity, repeated, last);
        SummaryFilter.ExclusionResult result = SummaryFilter.excludePublished(input,
                Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 2)));
        check(result.excludedCount == 3 && result.messages.equals(Arrays.asList(first, first, last)),
                "excludedCount counts input occurrences; exclusion must not deduplicate or reorder remaining sources");
        SummaryFilter.ExclusionResult emptySet = SummaryFilter.excludePublished(input, Collections.emptySet());
        check(emptySet.excludedCount == 0 && emptySet.messages.equals(input) && emptySet.messages != input,
                "empty confirmation set must copy the complete input without changing its occurrences");
        SummaryFilter.ExclusionResult emptyRange = SummaryFilter.excludePublished(Collections.emptyList(),
                Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 2)));
        check(emptyRange.messages.isEmpty() && emptyRange.excludedCount == 0,
                "confirmed records outside an empty candidate range must not create replacements or inflate counts");
        SummaryFilter.ExclusionResult all = SummaryFilter.excludePublished(Arrays.asList(repeated, duplicateIdentity),
                Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 2)));
        check(all.messages.isEmpty() && all.excludedCount == 2, "an entirely excluded range must remain empty without backfill");
    }

    private static void publishedExclusionIsAnImmutableSnapshot() {
        SummaryMessage excluded = message(1, OWNER, "published"), retained = message(2, 55, "retained");
        ArrayList<SummaryMessage> input = new ArrayList<>(Arrays.asList(excluded, retained));
        Set<SummaryFilter.PublishedMessageId> confirmed = new HashSet<>(Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 1)));
        SummaryFilter.ExclusionResult result = SummaryFilter.excludePublished(input, confirmed);
        input.clear();
        input.add(message(3, 55, "later input"));
        confirmed.clear();
        confirmed.add(new SummaryFilter.PublishedMessageId(DIALOG, 2));
        check(result.messages.equals(Collections.singletonList(retained)) && result.excludedCount == 1,
                "later caller list/set edits changed an earlier exclusion result");
        immutable(() -> result.messages.add(excluded), "exclusion result allowed additions");
        immutable(() -> result.messages.set(0, excluded), "exclusion result allowed replacements");
        immutable(() -> result.messages.remove(0), "exclusion result allowed removals");
        immutable(() -> result.messages.clear(), "exclusion result allowed clearing");
        SummaryFilter.ExclusionResult emptySet = SummaryFilter.excludePublished(input, Collections.emptySet());
        input.clear();
        check(emptySet.messages.size() == 1 && emptySet.messages.get(0).id == 3,
                "empty confirmation fast path leaked the caller's mutable input list");
        immutable(() -> emptySet.messages.clear(), "empty confirmation fast path returned a mutable result");
    }

    private static void publishedExclusionBeforeSelfContext() {
        List<SummaryMessage> input = Arrays.asList(message(1, 55, "published reply parent"),
                message(2, 55, "ordinary before"), message(3, 55, "published neighbor before"),
                full(4, 55, "self-related reply", true, 1, DIALOG, true, true),
                message(5, 55, "published neighbor after"), message(6, 55, "ordinary after"),
                message(7, 55, "outside bounded context"));
        SummaryFilter.Options self = new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, "");
        check(ids(SummaryFilter.apply(input, OWNER, self)).equals("1,3,4,5"),
                "fixture must exercise both excluded reply-parent and excluded neighbor context");
        Set<SummaryFilter.PublishedMessageId> confirmed = new HashSet<>(Arrays.asList(
                new SummaryFilter.PublishedMessageId(DIALOG, 1), new SummaryFilter.PublishedMessageId(DIALOG, 3),
                new SummaryFilter.PublishedMessageId(DIALOG, 5)));
        SummaryFilter.ExclusionResult remaining = SummaryFilter.excludePublished(input, confirmed);
        SummaryFilter.Result selected = SummaryFilter.apply(remaining.messages, OWNER, self);
        check(remaining.excludedCount == 3 && remaining.messages.size() == 4,
                "exclusion must report the selected-range reduction before normal filtering");
        check(ids(selected).equals("2,4,6") && selected.matchedCount == 1 && selected.contextCount == 2,
                "self context reintroduced an excluded parent/neighbor or lost surviving source order");
        for (SummaryMessage message : selected.messages) {
            check(!confirmed.contains(new SummaryFilter.PublishedMessageId(message.dialogId, message.id)),
                    "self-related context contains a confirmed published summary");
        }
        check(selected.coverageNote.contains("本次范围内 4 条唯一文字"),
                "normal filtering must describe the post-exclusion snapshot it actually received");
        SummaryFilter.ExclusionResult allExcluded = SummaryFilter.excludePublished(Collections.singletonList(input.get(3)),
                Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 4)));
        check(SummaryFilter.apply(allExcluded.messages, OWNER, self).messages.isEmpty(),
                "self-related selection fetched or restored a message after the entire candidate range was excluded");
    }

    private static void publishedExclusionRejectsInvalidInputs() {
        invalid(() -> SummaryFilter.excludePublished(null, Collections.emptySet()), "null source list accepted");
        invalid(() -> SummaryFilter.excludePublished(Collections.emptyList(), null), "null confirmation set accepted");
        invalid(() -> SummaryFilter.excludePublished(Arrays.asList(message(1, 55, "valid"), null), Collections.emptySet()),
                "null source element silently ignored");
        Set<SummaryFilter.PublishedMessageId> nullIdentity = new HashSet<>();
        nullIdentity.add(null);
        invalid(() -> SummaryFilter.excludePublished(Collections.emptyList(), nullIdentity),
                "invalid confirmation member silently ignored for an empty candidate range");
    }

    private static void excludedUserIdentityAndCounts() {
        long uid = 9007199254740993L;
        List<SummaryMessage> sources = Arrays.asList(message(1, uid, "blocked"),
                message(2, uid - 1, "same name, distinct exact UID"), message(3, -uid, "channel identity"),
                message(4, 0, "unknown sender"), message(5, uid, "blocked again"), message(5, uid, "duplicate"));
        SummaryFilter.Options options = new SummaryFilter.Options(SummaryFilter.Mode.ALL, 0, "", Collections.singleton(uid));
        SummaryFilter.Result result = SummaryFilter.apply(sources, OWNER, options);
        check(ids(result).equals("2,3,4") && result.excludedSenderCount == 2 && result.matchedCount == 3,
                "UID exclusion must be exact, positive and count unique sources");
        check(result.filtered && options.hasFilters(), "UID exclusions must block incremental cursor advancement");
        check(result.coverageNote.contains("原有 5 条唯一文字") && result.coverageNote.contains("排除 2 条")
                && result.coverageNote.contains("不补抓更早消息"), "coverage note must explain actual reduction of the chosen range");
        check(sources.size() == 6 && sources.get(0).senderId == uid, "exclusion mutated source snapshot");
        SummaryFilter.Result none = SummaryFilter.apply(Collections.singletonList(message(1, 77, "ordinary")), OWNER, options);
        check(none.excludedSenderCount == 0 && none.filtered, "non-matching exclusion list must still leave cursor untouched");
        SummaryFilter.Result all = SummaryFilter.apply(Collections.singletonList(message(1, uid, "all blocked")), OWNER, options);
        check(all.messages.isEmpty() && all.filtered && all.excludedSenderCount == 1, "fully excluded input must not fall back to full history");
        SummaryFilter.Result conflictingDuplicate = SummaryFilter.apply(Arrays.asList(message(1, 77, "older copy"), message(1, uid, "new copy")), OWNER, options);
        check(conflictingDuplicate.messages.isEmpty() && conflictingDuplicate.excludedSenderCount == 1,
                "conflicting duplicate sender metadata bypassed exclusion");
        SummaryFilter.Result requiredAndExcluded = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.ALL, uid, "", Collections.singleton(uid)));
        check(requiredAndExcluded.messages.isEmpty(), "sender include must not override explicit UID exclusion");
    }

    private static void excludedUsersNeverReappearAsContext() {
        List<SummaryMessage> sources = Arrays.asList(message(1, 77, "blocked parent"), message(2, 55, "before"),
                full(3, 77, "blocked mentioning self", true, 0, 0, false, false),
                full(4, 55, "retained mentioning self", true, 1, DIALOG, true, true),
                message(5, 77, "blocked neighbor"), message(6, 55, "after"), message(7, 55, "outside"));
        SummaryFilter.Options options = new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 0, "", Collections.singleton(77L));
        SummaryFilter.Result result = SummaryFilter.apply(sources, OWNER, options);
        check(ids(result).equals("2,4,6") && result.excludedSenderCount == 3 && result.matchedCount == 1 && result.contextCount == 2,
                "excluded sender restored as self match, neighbor or reply parent");
        for (SummaryMessage message : result.messages) check(message.senderId != 77, "hard exclusion leaked into final model context");
        SummaryFilter.Result focus = SummaryFilter.apply(sources, OWNER,
                new SummaryFilter.Options(SummaryFilter.Mode.FOCUS_SELF, 0, "", Collections.singleton(77L)));
        check(ids(focus).equals("2,4,6,7") && focus.filtered, "focus mode ignored UID exclusion");
        SummaryFilter.ExclusionResult published = SummaryFilter.excludePublished(sources,
                Collections.singleton(new SummaryFilter.PublishedMessageId(DIALOG, 2)));
        SummaryFilter.Result combined = SummaryFilter.apply(published.messages, OWNER, options);
        check(ids(combined).equals("4,6") && published.excludedCount == 1 && combined.excludedSenderCount == 3,
                "published and UID exclusions must combine before self-related context");
    }

    private static void excludedOptionsAreImmutable() {
        Set<Long> ids = new HashSet<>(Arrays.asList(12L, 13L));
        SummaryFilter.Options options = new SummaryFilter.Options(SummaryFilter.Mode.FILTER_SELF, 99, "release", ids);
        ids.clear();
        check(options.excludedSenderIds.equals(new HashSet<>(Arrays.asList(12L, 13L))), "caller mutated task UID snapshot");
        immutable(() -> options.excludedSenderIds.clear(), "options exposed mutable UID collection");
        SummaryFilter.Options replacement = options.withExcludedSenderIds(Collections.singleton(22L));
        check(replacement.mode == options.mode && replacement.senderId == options.senderId && replacement.keyword.equals(options.keyword)
                && replacement.excludedSenderIds.equals(Collections.singleton(22L)) && options.excludedSenderIds.size() == 2,
                "replacing UID list lost other filters or mutated running task options");
        for (Long uid : Arrays.asList(0L, -1L, (Long) null)) {
            invalid(() -> options.withExcludedSenderIds(Collections.singleton(uid)), "non-user UID accepted");
        }
        invalid(() -> options.withExcludedSenderIds(null), "null UID list silently disabled exclusion");
        check(SummaryFilter.Options.DEFAULT.excludedSenderIds.isEmpty() && !SummaryFilter.Options.DEFAULT.hasFilters(),
                "backward-compatible defaults unexpectedly filter history");
    }

    private static SummaryMessage message(int id, long sender, String text) {
        return full(id, sender, text, false, 0, 0, false, false);
    }
    private static SummaryMessage full(int id, long sender, String text, boolean mention,
            int replyId, long replyDialog, boolean known, boolean replySelf) {
        return new SummaryMessage(DIALOG, id, id, "Same name", text, sender, replyId,
                replyDialog, mention, sender == OWNER, 0, known, replySelf);
    }
    private static String ids(SummaryFilter.Result result) {
        StringBuilder out = new StringBuilder();
        for (SummaryMessage m : result.messages) { if (out.length() > 0) out.append(','); out.append(m.id); }
        return out.toString();
    }
    private static void invalid(Runnable operation, String reason) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isEmpty(), "invalid input needs an explicit error");
            return;
        }
        throw new AssertionError(reason);
    }
    private static void immutable(Runnable operation, String reason) {
        try { operation.run(); }
        catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
