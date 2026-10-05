package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Exercises date selection through the real loader and deterministic queued Telegram transport. */
public final class SummaryHistoryDateTest {
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000;
    private static final long DIALOG = -100;
    private static final int TOPIC = 42;
    private static final int SERVER_NOW = epoch("2026-10-05T12:00:00Z");
    private static final ConnectionsManager network = ConnectionsManager.getInstance(ACCOUNT);
    private static final MessagesController controller = MessagesController.getInstance(ACCOUNT);
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static int assertions;
    private static int passed;

    private static final class Result implements SummaryHistoryLoader.Callback {
        SummaryHistoryLoader.Result loaded;
        String error;
        int calls;
        final List<Integer> scans = new ArrayList<>();
        final List<Integer> texts = new ArrayList<>();

        @Override public void onLoaded(SummaryHistoryLoader.Result value) { loaded = value; calls++; }
        @Override public void onError(String value) { error = value; calls++; }
        @Override public void onProgress(int scanned, int textCount) {
            scans.add(scanned);
            texts.add(textCount);
        }
    }

    public static void main(String[] args) {
        test("past date seeks its exclusive end and paginates by ID", SummaryHistoryDateTest::pastDate);
        test("invalid, future and unrepresentable dates never request history", SummaryHistoryDateTest::invalidDates);
        test("Gregorian leap day and representable epoch date remain valid", SummaryHistoryDateTest::leapDate);
        test("civil day respects DST, half-hour offsets and missing midnight", SummaryHistoryDateTest::civilDayBoundaries);
        test("entirely skipped civil date is rejected", SummaryHistoryDateTest::skippedDate);
        test("today and selected current date freeze server clock and phone timezone", SummaryHistoryDateTest::todaySnapshot);
        test("future is judged in the frozen phone timezone", SummaryHistoryDateTest::localFuture);
        test("topic root cannot end or skip date pagination", SummaryHistoryDateTest::topicAnchor);
        test("other topic cannot establish the selected date boundary", SummaryHistoryDateTest::wrongTopic);
        test("cancelled and replaced date requests cannot deliver stale data", SummaryHistoryDateTest::cancelAndReplace);
        test("owner changes, later RPC denial and timeout fail the entire date load", SummaryHistoryDateTest::failedLoad);
        test("selected date text cap produces an explicit partial result", SummaryHistoryDateTest::textCap);
        test("scan and page guards bound selected dates with filtered history", SummaryHistoryDateTest::scanGuards);
        System.out.println("SummaryHistoryDateTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void pastDate() {
        reset(false, "UTC");
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int lower = start(selected, "UTC"), upper = start(selected.plusDays(1), "UTC");
        Result result = load(selected, 0);
        TLRPC.TL_messages_getHistory first = history();
        check(first.offset_date == upper && first.offset_id == 0 && first.max_id == 0,
                "old date must seek its own end instead of scanning from current history");
        check(first.limit == 100 && first.add_offset == 0, "selected date changed normal backwards page window");
        TLRPC.Message media = message(97, lower + 500, "excluded caption");
        media.media = new TLRPC.TL_messageMediaPhoto();
        TLRPC.Message link = message(98, lower + 3600, "original https://example.invalid/source");
        link.media = new TLRPC.TL_messageMediaWebPage();
        // Unordered and short; the cutoff's second itself must not leak into the selected date.
        reply(media, message(101, upper, "next date"), link, message(100, upper - 1, "last second"));
        check(result.calls == 0, "short date page ended before proving the date boundary");
        TLRPC.TL_messages_getHistory next = history();
        check(next.offset_id == 97 && next.offset_date == 0,
                "later date pages must advance filtered-message IDs without reapplying offset_date");
        check(next.max_id == 101, "snapshot upper ID was taken from excluded later-date message");
        reply(message(95, lower - 1, "previous date"), message(97, lower + 500, "overlap"),
                message(96, lower, "midnight"));
        complete(result, 3);
        check(ids(result).equals("96,98,100"), "date boundaries, duplicate exclusion or chronological order failed");
        check(result.loaded.messages.get(1).text.equals(link.message), "date load replaced original link text");
        check(result.loaded.messages.stream().allMatch(m -> m.dialogId == DIALOG), "date sources lost dialog identity");
        assertCoverage(result, selected, "UTC", lower, upper);
        check(network.sent.size() == 2, "date seek scanned irrelevant newer history or an unnecessary older page");
        clean();
    }

    private static void invalidDates() {
        int[][] values = {
                {2024, 0, 1}, {2024, 13, 1}, {2024, 2, 30}, {2025, 2, 29},
                {2024, 4, 31}, {2024, 1, 0}, {2024, 1, -1}, {0, 1, 1},
                {-2024, 1, 1}, {1969, 12, 31},
                {2026, 10, 6}, {2027, 1, 1}, {2038, 1, 20}, {Integer.MAX_VALUE, 1, 1}
        };
        for (int[] value : values) {
            reset(false, "UTC");
            Result result = new Result();
            new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadDate(value[0], value[1], value[2], result);
            AndroidUtilities.drain();
            rejected(result, "invalid date " + value[0] + "-" + value[1] + "-" + value[2]);
            check(network.sent.isEmpty(), "invalid or future date issued an RPC");
            clean();
        }
        reset(false, "UTC");
        network.currentTimeOverride = Integer.MAX_VALUE;
        Result overflow = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadDate(2038, 1, 19, overflow);
        AndroidUtilities.drain();
        rejected(overflow, "current date whose exclusive current-second cutoff overflows Telegram date storage");
        check(network.sent.isEmpty(), "unrepresentable exclusive cutoff wrapped into an RPC date");
        clean();
    }

    private static void leapDate() {
        reset(false, "UTC");
        LocalDate selected = LocalDate.of(2020, 2, 29);
        int lower = start(selected, "UTC"), upper = start(selected.plusDays(1), "UTC");
        Result result = load(selected, 0);
        check(history().offset_date == upper, "valid Gregorian leap date rolled into March");
        reply(message(10, upper - 1, "leap date end"), message(9, lower, "leap date start"),
                message(8, lower - 1, "February 28"));
        complete(result, 2);
        assertCoverage(result, selected, "UTC", lower, upper);
        clean();

        reset(false, "UTC");
        LocalDate epochDate = LocalDate.of(1970, 1, 1);
        Result epochResult = load(epochDate, 0);
        check(history().offset_date == 86400, "zero inclusive lower bound incorrectly rejected a positive date seek");
        reply(message(1, 1, "positive Telegram timestamp"));
        reply();
        complete(epochResult, 1);
        assertCoverage(epochResult, epochDate, "UTC", 0, 86400);
        clean();
    }

    private static void civilDayBoundaries() {
        assertDay("America/New_York", LocalDate.of(2024, 3, 10), 23 * 3600);
        assertDay("America/New_York", LocalDate.of(2024, 11, 3), 25 * 3600);
        assertDay("America/Havana", LocalDate.of(2020, 11, 1), 25 * 3600);
        repeatedMidnightText();
        assertDay("Australia/Lord_Howe", LocalDate.of(2024, 10, 6), 23 * 3600 + 1800);
        assertDay("Australia/Lord_Howe", LocalDate.of(2024, 4, 7), 24 * 3600 + 1800);
        assertDay("Asia/Kolkata", LocalDate.of(2024, 2, 29), 24 * 3600);
        assertDay("Asia/Kathmandu", LocalDate.of(2024, 2, 29), 24 * 3600);
        // Midnight does not exist on November 4: its first valid time is 01:00. The following
        // day's upper bound must return to 00:00 instead of inheriting the normalized 01:00.
        assertDay("America/Sao_Paulo", LocalDate.of(2018, 11, 4), 23 * 3600);
        assertDay("America/Sao_Paulo", LocalDate.of(2018, 11, 3), 24 * 3600);
    }

    private static void repeatedMidnightText() {
        String zone = "America/Havana";
        reset(false, zone);
        LocalDate selected = LocalDate.of(2024, 11, 3);
        int lower = start(selected, zone), upper = start(selected.plusDays(1), zone);
        int firstHalfHour = epoch("2024-11-03T04:30:00Z");
        int secondHalfHour = epoch("2024-11-03T05:30:00Z");
        check(firstHalfHour == lower + 1800 && upper - lower == 25 * 3600,
                "Havana repeated-midnight fixture no longer identifies the first 00:30");
        check(NOTE_TIME.format(Instant.ofEpochSecond(firstHalfHour).atZone(ZoneId.of(zone)))
                .equals("2024-11-03 00:30:00")
                && NOTE_TIME.format(Instant.ofEpochSecond(secondHalfHour).atZone(ZoneId.of(zone)))
                .equals("2024-11-03 00:30:00"), "Havana fixture does not contain both occurrences of 00:30");
        Result result = load(selected, 0);
        check(history().offset_date == upper, "Havana date seek used the wrong exclusive end");
        reply(message(101, upper, "next date"), message(100, secondHalfHour, "second 00:30 text"),
                message(99, firstHalfHour, "first 00:30 text"), message(98, lower - 1, "previous date"));
        complete(result, 2);
        check(ids(result).equals("99,100") && result.loaded.messages.get(0).text.equals("first 00:30 text"),
                "Havana selected date omitted actual text from its first repeated half-hour");
        assertCoverage(result, selected, zone, lower, upper);
        clean();
    }

    private static void assertDay(String zone, LocalDate selected, int expectedDuration) {
        reset(false, zone);
        int lower = start(selected, zone), upper = start(selected.plusDays(1), zone);
        check(upper - lower == expectedDuration, "reference timezone fixture changed: " + zone + " " + selected);
        Result result = load(selected, 0);
        check(history().offset_date == upper, "incorrect civil-day upper bound: " + zone + " " + selected);
        reply(message(14, upper, "outside end"), message(13, upper - 1, "inside end"),
                message(12, lower, "inside start"), message(11, lower - 1, "outside start"));
        complete(result, 2);
        check(ids(result).equals("12,13"), "incorrect civil-day inclusion: " + zone + " " + selected);
        assertCoverage(result, selected, zone, lower, upper);
        clean();
    }

    private static void skippedDate() {
        reset(false, "Pacific/Apia");
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadDate(2011, 12, 30, result);
        AndroidUtilities.drain();
        rejected(result, "entirely skipped local civil date");
        check(network.sent.isEmpty(), "skipped date was normalized into the following day");
        clean();
    }

    private static void todaySnapshot() {
        // Fixed times are unrelated to the host date. Include an ordinary second, the last
        // second of the civil day and its very first second, for both entry points.
        int[] instants = {epoch("2024-02-29T06:30:00Z"), epoch("2024-02-29T18:29:59Z"),
                epoch("2024-02-28T18:30:00Z")};
        for (boolean explicitDate : new boolean[] {false, true}) {
            for (int serverNow : instants) {
                reset(false, "Asia/Kolkata");
                network.currentTimeOverride = serverNow;
                LocalDate selected = LocalDate.of(2024, 2, 29);
                int lower = start(selected, "Asia/Kolkata");
                int upper = Math.min(start(selected.plusDays(1), "Asia/Kolkata"), serverNow + 1);
                Result result = new Result();
                SummaryHistoryLoader loader = new SummaryHistoryLoader(ACCOUNT, DIALOG, 0);
                if (explicitDate) loader.loadDate(2024, 2, 29, result);
                else loader.loadToday(result);
                // Change both inputs while the initial UI action is queued. The call already
                // captured its range, so neither this nor later page transitions may alter it.
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
                network.currentTimeOverride = SERVER_NOW;
                AndroidUtilities.drain();
                check(history().offset_date == upper, "today's cutoff followed changed clock/timezone");
                reply(message(100, upper - 1, "captured current second"));
                check(result.calls == 0, "today's short page ended without proving lower coverage");
                check(history().offset_date == 0 && history().offset_id == 100,
                        "today's second page changed back into a time-based request");
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
                network.currentTimeOverride += 86400;
                reply(message(99, upper, "arrived after capture"), message(98, lower, "start of frozen day"),
                        message(97, lower - 1, "previous day"));
                complete(result, 2);
                check(ids(result).equals("98,100"),
                        "frozen today source range included post-snapshot data");
                assertCoverage(result, selected, "Asia/Kolkata", lower, upper);
                check(!result.loaded.coverageNote.contains("America/Los_Angeles")
                        && !result.loaded.coverageNote.contains("Pacific/Auckland"),
                        "coverage displayed a later phone timezone");
                clean();
            }
        }
    }

    private static void localFuture() {
        reset(false, "Pacific/Honolulu");
        network.currentTimeOverride = epoch("2024-03-01T05:00:00Z"); // Local February 29, 19:00.
        Result future = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadDate(2024, 3, 1, future);
        AndroidUtilities.drain();
        rejected(future, "tomorrow in the phone timezone");
        check(network.sent.isEmpty(), "UTC date permitted a future phone-local day");
        Result today = load(LocalDate.of(2024, 2, 29), 0);
        check(history().offset_date == network.currentTimeOverride + 1,
                "current phone-local date was not capped to server now");
        reply();
        complete(today, 0);
        clean();
    }

    private static void topicAnchor() {
        reset(true, "UTC");
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int lower = start(selected, "UTC"), upper = start(selected.plusDays(1), "UTC");
        Result result = load(selected, TOPIC);
        TLRPC.TL_messages_getReplies first = replies();
        check(first.msg_id == TOPIC && first.offset_date == upper && first.offset_id == 0,
                "selected topic did not seek its date range");
        reply(root(lower - 86400), topicMessage(300, upper - 1, "latest topic text"),
                topicMessage(299, lower + 30, " "));
        check(result.calls == 0, "old topic anchor incorrectly proved the selected date start");
        check(replies().offset_id == 299 && replies().offset_date == 0 && replies().msg_id == TOPIC,
                "root anchor skipped actual topic history or changed scope");
        reply(root(lower - 86400), topicMessage(298, lower, "first topic text"),
                topicMessage(297, lower - 1, "previous topic day"));
        complete(result, 2);
        check(ids(result).equals("298,300") && result.loaded.messages.stream().allMatch(m -> m.topicId == TOPIC),
                "date/topic source identities were changed");
        check(result.loaded.coverageNote.contains("Topic #42"), "coverage omitted specific topic");
        clean();

        reset(true, "UTC");
        Result rootOnly = load(selected, TOPIC);
        reply(topicMessage(300, lower + 100, "only visible reply"));
        reply(root(lower - 86400));
        complete(rootOnly, 1);
        check(network.sent.size() == 2, "root-only page looped instead of completing visible topic history");
        clean();
    }

    private static void wrongTopic() {
        reset(true, "UTC");
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int lower = start(selected, "UTC");
        Result result = load(selected, TOPIC);
        TLRPC.Message other = topicMessage(199, lower - 1, "belongs to another topic");
        other.reply_to.reply_to_top_id = 43;
        reply(topicMessage(200, lower + 10, "correct topic"), other);
        rejected(result, "other topic before selected midnight");
        clean();
    }

    private static void cancelAndReplace() {
        reset(false, "UTC");
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int lower = start(selected, "UTC");
        SummaryHistoryLoader loader = new SummaryHistoryLoader(ACCOUNT, DIALOG, 0);
        Result cancelled = new Result();
        loader.loadDate(2024, 2, 29, cancelled); AndroidUtilities.drain();
        ConnectionsManager.Pending old = network.next();
        loader.cancel(); AndroidUtilities.drain();
        old.delegate.run(page(message(100, lower, "late cancelled reply")), null); AndroidUtilities.drain();
        check(cancelled.calls == 0, "cancelled date load delivered a callback");
        check(!network.cancelledGuids.isEmpty(), "date cancellation did not cancel its transport scope");
        clean();
        Result replaced = new Result();
        loader.loadDate(2024, 2, 29, replaced); AndroidUtilities.drain();
        ConnectionsManager.Pending superseded = network.next();
        Result latest = new Result();
        loader.loadDate(2024, 3, 1, latest); AndroidUtilities.drain();
        superseded.delegate.run(page(message(101, lower, "late superseded reply")), null);
        AndroidUtilities.drain();
        check(replaced.calls == 0 && cancelled.calls == 0, "replacement revived stale date callbacks");
        int newLower = start(LocalDate.of(2024, 3, 1), "UTC");
        check(history().offset_date == start(LocalDate.of(2024, 3, 2), "UTC"), "replacement retained old date");
        reply(message(102, newLower, "replacement date"), message(101, newLower - 1, "previous date"));
        complete(latest, 1);
        check(latest.loaded.messages.get(0).id == 102, "replacement accepted stale sources");
        clean();
    }

    private static void failedLoad() {
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int lower = start(selected, "UTC");
        for (int variant = 0; variant < 4; variant++) {
            reset(false, "UTC");
            Result result = load(selected, 0);
            if (variant == 0) {
                network.reply(page(message(100, lower, "old account data")), null);
                UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER + 1);
                AndroidUtilities.drain();
            } else if (variant == 1) {
                reply(message(100, lower + 20, "read first page"));
                TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400; error.text = "CHANNEL_PRIVATE";
                network.reply(null, error); AndroidUtilities.drain();
                check(result.error != null && result.error.contains("CHANNEL_PRIVATE"), "RPC error lost useful server reason");
            } else if (variant == 2) {
                reply(message(100, lower + 20, "read first page"));
                AndroidUtilities.fireTimers();
            } else {
                controller.getChat(-DIALOG).kicked = true;
                reply(message(100, lower, "no longer readable"));
            }
            rejected(result, "failed date load variant " + variant);
            clean();
        }

        reset(false, "UTC");
        SummaryHistoryLoader staleOwner = new SummaryHistoryLoader(ACCOUNT, DIALOG, 0);
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER + 1);
        Result result = new Result(); staleOwner.loadDate(2024, 2, 29, result); AndroidUtilities.drain();
        rejected(result, "loader created for a previous account owner");
        check(network.sent.isEmpty(), "stale owner began a selected date RPC");
        clean();
    }

    private static void textCap() {
        reset(false, "UTC");
        LocalDate selected = LocalDate.of(2024, 2, 29);
        int upper = start(selected.plusDays(1), "UTC");
        Result result = load(selected, 0);
        for (int p = 0; p < 20; p++) {
            TLRPC.Message[] messages = new TLRPC.Message[100];
            for (int i = 0; i < messages.length; i++) {
                int ordinal = p * 100 + i;
                messages[i] = message(30000 - ordinal, upper - 1 - ordinal, "selected date text " + ordinal);
            }
            reply(messages);
            if (p < 19) check(result.calls == 0, "selected date stopped before the text cap");
        }
        partial(result, 2000);
        check(result.loaded.scannedMessageCount == 2000 && network.sent.size() == 20,
                "text cap scanned extra pages or reported the wrong coverage");
        check(result.loaded.messages.get(0).id == 28001 && result.loaded.messages.get(1999).id == 30000,
                "text cap did not retain the newest selected-date texts in chronological order");
        check(result.loaded.coverageNote.contains("2000"), "partial coverage omitted its text cap");
        progressMonotonic(result);
        clean();
    }

    private static void scanGuards() {
        for (int pageSize : new int[] {100, 1}) {
            reset(false, "UTC");
            LocalDate selected = LocalDate.of(2024, 2, 29);
            int upper = start(selected.plusDays(1), "UTC");
            Result result = load(selected, 0);
            for (int p = 0; p < 100; p++) {
                TLRPC.Message[] messages = new TLRPC.Message[pageSize];
                for (int i = 0; i < pageSize; i++) {
                    int ordinal = p * pageSize + i;
                    messages[i] = message(30000 - ordinal, upper - 1 - ordinal, "media caption " + ordinal);
                    if (ordinal != 0) messages[i].media = new TLRPC.TL_messageMediaPhoto();
                }
                reply(messages);
                if (p < 99) check(result.calls == 0, "date scan guard stopped before its documented bounds");
            }
            partial(result, 1);
            check(result.loaded.scannedMessageCount == 100 * pageSize && network.sent.size() == 100,
                    "filtered history escaped the " + (pageSize == 100 ? "message" : "page") + " scan guard");
            check(result.loaded.coverageNote.contains("10000") && result.loaded.coverageNote.contains("100"),
                    "partial coverage omitted scan limits");
            progressMonotonic(result);
            clean();
        }
    }

    private static void assertCoverage(Result result, LocalDate selected, String zone, int lower, int upper) {
        String note = result.loaded.coverageNote;
        check(note.contains(selected.toString()) && note.contains(zone), "coverage omitted selected date or frozen timezone");
        check(note.contains(NOTE_TIME.format(Instant.ofEpochSecond(lower).atZone(ZoneId.of(zone)))),
                "coverage omitted selected date inclusive start");
        check(note.contains(NOTE_TIME.format(Instant.ofEpochSecond(upper).atZone(ZoneId.of(zone)))),
                "coverage omitted selected date exclusive end");
    }

    private static void complete(Result result, int count) {
        check(result.calls == 1 && result.error == null && result.loaded != null,
                "selected date did not finish successfully: " + result.error);
        check(result.loaded.complete && !result.loaded.partial && !result.loaded.truncated,
                "fully scanned date was marked partial");
        check(result.loaded.messages.size() == count, "wrong selected date text count: " + result.loaded.messages.size());
    }

    private static void partial(Result result, int count) {
        check(result.calls == 1 && result.error == null && result.loaded != null, "date limit did not return its observed partial range");
        check(result.loaded.truncated && result.loaded.partial && !result.loaded.complete
                && result.loaded.coveredThroughId == 0 && !result.loaded.hasMore,
                "partial selected date falsely advanced complete history coverage");
        check(result.loaded.messages.size() == count, "partial date included filtered or excess text");
    }

    private static void rejected(Result result, String context) {
        check(result.calls == 1 && result.loaded == null && result.error != null && !result.error.isEmpty(),
                context + " must fail once without successful sources");
    }

    private static void progressMonotonic(Result result) {
        check(!result.scans.isEmpty(), "long selected date omitted progress");
        int previousScan = 0, previousText = 0;
        for (int i = 0; i < result.scans.size(); i++) {
            check(result.scans.get(i) >= previousScan && result.texts.get(i) >= previousText
                    && result.texts.get(i) <= result.scans.get(i), "date scan progress regressed");
            previousScan = result.scans.get(i); previousText = result.texts.get(i);
        }
        check(previousScan == result.loaded.scannedMessageCount && previousText == result.loaded.messages.size(),
                "terminal date progress disagrees with actual sources");
    }

    private static Result load(LocalDate selected, int topic) {
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, topic).loadDate(selected.getYear(), selected.getMonthValue(),
                selected.getDayOfMonth(), result);
        AndroidUtilities.drain();
        check(result.calls == 0 && network.pending.size() == 1, "valid selected date failed before history response: " + result.error);
        return result;
    }

    private static void reset(boolean forum, String zone) {
        AndroidUtilities.reset(); network.reset();
        network.currentTimeOverride = SERVER_NOW;
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        controller.chats.clear(); controller.users.clear(); controller.fullChats.clear(); controller.inputPeerOverrides.clear();
        UserConfig.selectedAccount = ACCOUNT;
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        TLRPC.Chat chat = new TLRPC.TL_chat(); chat.id = -DIALOG; chat.title = "Date test group"; chat.forum = forum;
        controller.chats.put(chat.id, chat);
        TLRPC.User sender = new TLRPC.User(); sender.id = 55; sender.first_name = "Date test sender";
        controller.users.put(sender.id, sender);
    }

    private static void clean() {
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "date load leaked request or timer");
    }

    private static TLRPC.TL_messages_getHistory history() {
        check(network.next().request instanceof TLRPC.TL_messages_getHistory, "expected group history request");
        return (TLRPC.TL_messages_getHistory) network.next().request;
    }

    private static TLRPC.TL_messages_getReplies replies() {
        check(network.next().request instanceof TLRPC.TL_messages_getReplies, "expected topic history request");
        return (TLRPC.TL_messages_getReplies) network.next().request;
    }

    private static TLRPC.Peer peer() { TLRPC.Peer peer = new TLRPC.TL_peerChat(); peer.chat_id = -DIALOG; return peer; }
    private static TLRPC.Message message(int id, int date, String text) {
        TLRPC.Message message = new TLRPC.TL_message(); message.id = id; message.date = date; message.message = text;
        message.peer_id = peer(); message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = 55;
        return message;
    }
    private static TLRPC.Message topicMessage(int id, int date, String text) {
        TLRPC.Message message = message(id, date, text); message.reply_to = new TLRPC.TL_messageReplyHeader();
        message.reply_to.forum_topic = true; message.reply_to.reply_to_top_id = TOPIC; return message;
    }
    private static TLRPC.Message root(int date) {
        TLRPC.Message root = new TLRPC.TL_messageService(); root.id = TOPIC; root.date = date;
        root.peer_id = peer(); root.action = new TLRPC.TL_messageActionTopicCreate(); return root;
    }
    private static TLRPC.TL_messages_messages page(TLRPC.Message... messages) {
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        java.util.Collections.addAll(response.messages, messages); return response;
    }
    private static void reply(TLRPC.Message... messages) { network.reply(page(messages), null); AndroidUtilities.drain(); }
    private static int epoch(String value) { return Math.toIntExact(Instant.parse(value).getEpochSecond()); }
    private static int start(LocalDate date, String zone) { return Math.toIntExact(date.atStartOfDay(ZoneId.of(zone)).toEpochSecond()); }
    private static String ids(Result result) {
        StringBuilder ids = new StringBuilder();
        for (SummaryMessage message : result.loaded.messages) { if (ids.length() > 0) ids.append(','); ids.append(message.id); }
        return ids.toString();
    }
    private static void test(String name, Runnable action) {
        TimeZone previous = TimeZone.getDefault();
        try { action.run(); passed++; System.out.println("PASS " + name); }
        finally { TimeZone.setDefault(previous); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
