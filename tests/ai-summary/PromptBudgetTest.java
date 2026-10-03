package org.telegram.messenger.ai;

import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configurable estimates, lossless reply-aware segments and session-only self emphasis. */
public final class PromptBudgetTest {
    private static int assertions;
    public static void main(String[] args) {
        budgets();
        replyChains();
        crossSegmentTransfer();
        focusMetadata();
        System.out.println("PromptBudgetTest: " + assertions + " assertions passed");
    }

    private static void budgets() {
        PromptOptions options = PromptOptions.DEFAULT;
        check(AiSummaryPrompt.dataBudget(options, 12000, 512) > AiSummaryPrompt.dataBudget(options, 6000, 512), "larger input setting changes usable source budget");
        check(AiSummaryPrompt.dataBudget(options, 6000, 1024) < AiSummaryPrompt.dataBudget(options, 6000, 512), "requested output has an explicit reserve");
        fails(() -> AiSummaryPrompt.dataBudget(options, 2047, 64), "context lower bound");
        fails(() -> AiSummaryPrompt.dataBudget(options, 32001, 64), "context upper bound");
        fails(() -> AiSummaryPrompt.dataBudget(options, 2048, 512), "insufficient data space fails before requesting a model");
        fails(() -> AiSummaryPrompt.dataBudget(options, 32000, 8192), "output reserve cannot exceed total context estimate");
        List<SummaryMessage> sources = Arrays.asList(message(10, repeat("Unicode😀\n\"\\", 700), 0), message(20, "末条原文", 0));
        List<String> chunks = AiSummaryPrompt.sourceChunks(sources, options, 2048, 64);
        check(chunks.size() > 1, "small configured budget produces several segments");
        assertLossless(sources, chunks);
        for (int i = 0; i < chunks.size(); i++) {
            String prompt = AiSummaryPrompt.sourcePrompt(chunks.get(i), i + 1, chunks.size(), options, 2048, 64);
            check(prompt.length() + AiSummaryPrompt.SYSTEM_PROMPT.length() + AiSummaryPrompt.outputReserveCharacters(64) <= 2048, "complete request fits configured character estimate");
        }
        List<String> merges = AiSummaryPrompt.mergeChunks(Arrays.asList("决定 [m1]", "接手 [m2]"), options, 2048, 64);
        String prompt = AiSummaryPrompt.mergePrompt(merges.get(0), options, 2048, 64);
        check(prompt.length() + AiSummaryPrompt.SYSTEM_PROMPT.length() + AiSummaryPrompt.outputReserveCharacters(64) <= 2048, "merge reserves same system/direction/output costs");
    }

    private static void replyChains() {
        int budget = AiSummaryPrompt.dataBudget(PromptOptions.DEFAULT, 6000, 512);
        List<SummaryMessage> sources = Arrays.asList(message(1, repeat("a", budget - 550), 0),
                message(2, repeat("部署由王负责。", 20), 0), message(3, "改由李接手，王不再负责。", 2));
        List<String> chunks = AiSummaryPrompt.sourceChunks(sources, PromptOptions.DEFAULT, 6000, 512);
        boolean together = false;
        for (String chunk : chunks) {
            java.util.Set<Integer> refs = AiSummaryPrompt.sourceReferences(chunk);
            if (refs.contains(2) && refs.contains(3)) together = true;
        }
        check(together, "small adjacent task/reassignment reply chain shares a chunk");
        assertLossless(sources, chunks);
        JSONObject last = findRecord(chunks, "[m3]");
        check("[m2]".equals(last.getString("reply_to_ref")), "reply target maps original IDs to stable snapshot refs");
        check(last.getInt("reply_to_id") == 2, "actual reply metadata preserved");
    }

    private static void crossSegmentTransfer() {
        List<SummaryMessage> sources = Arrays.asList(message(10, repeat("发布背景。", 1800) + "原方案由王负责。", 0),
                message(11, "取消原指派，现在改由李负责发布。", 10));
        List<String> chunks = AiSummaryPrompt.sourceChunks(sources, PromptOptions.DEFAULT, 6000, 512);
        check(chunks.size() > 2, "long parent crosses several segments");
        assertLossless(sources, chunks);
        JSONObject reply = findRecord(chunks, "[m2]");
        check(reply.getString("reply_to_ref").equals("[m1]") && reply.getString("text").contains("改由李"), "cross-segment reassignment relationship and text survive");
        for (String chunk : chunks) {
            boolean hasActualParent = false;
            for (String line : chunk.split("\n")) if (new JSONObject(line).getString("ref").equals("[m1]")) hasActualParent = true;
            check(AiSummaryPrompt.sourceReferences(chunk).contains(1) == hasActualParent, "reply_to_ref alone never authorizes citing absent parent text");
        }
    }

    private static void focusMetadata() {
        PromptOptions plain = new PromptOptions(PromptOptions.TODOS, "关注明确负责人");
        PromptOptions focus = plain.withFocusSelf(true);
        check(!plain.focusSelf && focus.focusSelf && !plain.equals(focus), "self emphasis is immutable and part of option identity");
        SummaryMessage source = new SummaryMessage(-10, 100, 1700000000, "同名用户", "明确回复当前账号", 999,
                99, -10, true, false, 0, true, true);
        List<String> chunks = AiSummaryPrompt.sourceChunks(Arrays.asList(source), focus, 6000, 512);
        JSONObject row = new JSONObject(chunks.get(0).trim());
        check(row.getLong("sender_id") == 999 && row.getInt("reply_to_id") == 99 && row.getLong("reply_to_dialog_id") == -10, "stable peer/reply identity included");
        check(row.getBoolean("mentioned_self") && row.getBoolean("reply_to_self_known")
                && row.getBoolean("reply_to_self") && !row.getBoolean("outgoing"), "reliable self metadata serialized");
        String prompt = AiSummaryPrompt.sourcePrompt(chunks.get(0), 1, 1, focus, 6000, 512);
        check(prompt.contains("不排除其他输入") && prompt.contains("不根据昵称推断身份"), "emphasis does not pretend to filter or infer identity");
        UserConfig.getInstance(0).setClientUserId(1000);
        PromptPreferences.save(0, 1000, -10, 0, PromptPreferences.Scope.CHAT, focus);
        check(!PromptPreferences.load(0, 1000, -10, 0).options.focusSelf, "self emphasis is not persisted as a saved direction");
        PromptPreferences.clearOwner(0, 1000);
    }

    private static void assertLossless(List<SummaryMessage> messages, List<String> chunks) {
        Map<String, StringBuilder> restored = new HashMap<>();
        for (String chunk : chunks) for (String line : chunk.split("\n")) {
            JSONObject row = new JSONObject(line);
            restored.computeIfAbsent(row.getString("ref"), ignored -> new StringBuilder()).append(row.getString("text"));
        }
        check(restored.size() == messages.size(), "no source reference created or lost");
        for (int i = 0; i < messages.size(); i++) check(messages.get(i).text.equals(restored.get("[m" + (i + 1) + "]").toString()), "all original Unicode source text retained at original ref");
    }
    private static JSONObject findRecord(List<String> chunks, String ref) {
        for (String chunk : chunks) for (String line : chunk.split("\n")) { JSONObject row = new JSONObject(line); if (ref.equals(row.getString("ref"))) return row; }
        throw new AssertionError("missing " + ref);
    }
    private static SummaryMessage message(int id, String text, int reply) { return new SummaryMessage(-10, id, 1700000000 + id, "sender", text, 55, reply, -10, false, false, 0, false, false); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
    private static void fails(Runnable action, String reason) { try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; } throw new AssertionError(reason); }
    private static void check(boolean condition, String reason) { assertions++; if (!condition) throw new AssertionError(reason); }
}
