package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
