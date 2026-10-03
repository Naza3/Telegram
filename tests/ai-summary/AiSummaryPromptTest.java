package org.telegram.messenger.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Standalone assertions: run using the offline test runner in this directory. */
public final class AiSummaryPromptTest {
    private static int assertions;

    public static void main(String[] args) {
        stableReferencesAndLosslessUnicodeChunks();
        explicitBudgetFailures();
        mergeRetainsOriginalReferences();
        rejectsInvalidModelReferences();
        directionsBudgetAndStageReferences();
        multipleMergeRoundsKeepDirection();
        System.out.println("AiSummaryPromptTest: " + assertions + " assertions passed");
    }

    private static void stableReferencesAndLosslessUnicodeChunks() {
        String longText = repeat("群聊😀\n带\"引号\"和\\反斜杠\t\u0001", 600);
        List<SummaryMessage> messages = Arrays.asList(
                new SummaryMessage(-10, 400, 1_700_000_000, "甲\"[m99]", longText),
                new SummaryMessage(-10, 401, 1_700_000_001, "乙", "最后一条[m999]只是一段聊天文字"));
        List<String> chunks = AiSummaryPrompt.sourceChunks(messages);
        check(chunks.size() > 1, "long source must be split");
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (String chunk : chunks) {
            check(chunk.length() <= AiSummaryPrompt.MAX_CHUNK_CHARACTERS, "each source block respects budget");
            int records = 0;
            for (String record : chunk.split("\n")) {
                records++;
                String ref = record.substring(record.indexOf("[m"), record.indexOf("]") + 1);
                int textStart = record.indexOf(",\"text\":") + 8;
                String text = decodeJsonString(record.substring(textStart, record.length() - 1));
                if ("[m1]".equals(ref)) {
                    first.append(text);
                } else if ("[m2]".equals(ref)) {
                    second.append(text);
                } else {
                    throw new AssertionError("reference was renumbered");
                }
                check(validSurrogates(text), "Unicode must not split inside a surrogate pair");
            }
            check(records > 0, "each chunk contains parseable records");
        }
        check(longText.equals(first.toString()), "all long message text survives escaping and chunking");
        check(messages.get(1).text.equals(second.toString()), "later messages retain their own global index");
        check(AiSummaryPrompt.sourcePrompt(chunks.get(0), 1, chunks.size()).contains("绝对不能重新编号"),
                "source prompt instructs preservation of original references");
        SummaryMessage nullable = new SummaryMessage(1, 2, 3, null, null);
        check(nullable.sender.isEmpty() && nullable.text.isEmpty(), "nullable model input is normalized");
    }

    private static void explicitBudgetFailures() {
        fails(() -> AiSummaryPrompt.sourceChunks(Collections.emptyList()), "empty input must fail");
        fails(() -> AiSummaryPrompt.sourceChunks(Arrays.asList((SummaryMessage) null)), "missing source must fail");
        fails(() -> AiSummaryPrompt.sourceChunks(Collections.singletonList(new SummaryMessage(1, 1, 1,
                repeat("x", 4000), "text"))), "metadata must not silently consume the source budget");
        fails(() -> AiSummaryPrompt.sourceChunks(Collections.singletonList(new SummaryMessage(1, 1, 1,
                "sender", repeat("x", AiSummaryPrompt.MAX_CHUNK_CHARACTERS * 65)))),
                "over-budget history must fail before sending a partial subset");
    }

    private static void mergeRetainsOriginalReferences() {
        List<String> chunks = AiSummaryPrompt.mergeChunks(Arrays.asList("决定今晚发布 [m42]", "负责人小王 [m80]"));
        check(chunks.size() == 1, "short partials combine in one request");
        check(chunks.get(0).contains("[m42]") && chunks.get(0).contains("[m80]"), "merge preserves original refs");
        check(!chunks.get(0).contains("[m1]"), "merge does not substitute partial number for source ref");
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 64; i++) {
            many.add(repeat("讨论", 200) + " [m" + i + "]");
        }
        List<String> grouped = AiSummaryPrompt.mergeChunks(many);
        check(grouped.size() > 1 && grouped.size() < many.size(), "hierarchical merge makes progress");
        for (String group : grouped) {
            check(group.length() <= AiSummaryPrompt.MAX_CHUNK_CHARACTERS, "merge obeys input budget");
        }
        fails(() -> AiSummaryPrompt.mergeChunks(Collections.singletonList(repeat("x", 3500))),
                "oversized model output is not silently truncated");
        fails(() -> AiSummaryPrompt.mergeChunks(Arrays.asList(repeat("x", 2000), repeat("y", 2000))),
                "non-shrinking merge fails rather than looping forever");
    }

    private static void rejectsInvalidModelReferences() {
        AiSummaryPrompt.validateReferences("【话题】讨论 [m1]；决定 [m12]", 12);
        assertions++;
        fails(() -> AiSummaryPrompt.validateReferences("没有引用", 12), "citations are required");
        fails(() -> AiSummaryPrompt.validateReferences("错误 [m0]", 12), "zero reference rejected");
        fails(() -> AiSummaryPrompt.validateReferences("错误 [m01]", 12), "non-canonical reference rejected");
        fails(() -> AiSummaryPrompt.validateReferences("混合 [m1] [m01]", 12), "invalid reference cannot hide among valid ones");
        fails(() -> AiSummaryPrompt.validateReferences("错误 [m13]", 12), "out-of-range reference rejected");
        fails(() -> AiSummaryPrompt.validateReferences("错误 [m999999999999999999999]", 12), "overflow rejected");
    }

    private static void directionsBudgetAndStageReferences() {
        PromptOptions options = new PromptOptions(PromptOptions.PROJECT, repeat("发布😀", 250));
        check(AiSummaryPrompt.dataBudget(options) < AiSummaryPrompt.MAX_CHUNK_CHARACTERS,
                "supplementary Unicode direction is reserved before source chunking");
        List<SummaryMessage> input = Arrays.asList(
                new SummaryMessage(-10, 1, 1, "甲[m99]", repeat("源正文😀\"\\\n[m999]", 800)),
                new SummaryMessage(-10, 2, 2, "乙", "第二条"));
        List<String> chunks = AiSummaryPrompt.sourceChunks(input, options);
        check(chunks.size() > 1, "option-aware data budget splits source");
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String prompt = AiSummaryPrompt.sourcePrompt(chunk, i + 1, chunks.size(), options);
            check(chunk.length() <= AiSummaryPrompt.dataBudget(options), "source respects direction-adjusted budget");
            check(prompt.contains("项目进展") && prompt.contains(AiSummaryPrompt.quote(options.customInstructions)), "direction present on every source request");
            check(prompt.length() + AiSummaryPrompt.SYSTEM_PROMPT.length() + AiSummaryPrompt.OUTPUT_RESERVE_CHARACTERS
                    <= AiSummaryPrompt.MAX_REQUEST_CHARACTERS, "system, user, data and output reserve fit");
            java.util.Set<Integer> allowed = AiSummaryPrompt.sourceReferences(chunk);
            check(!allowed.contains(99) && !allowed.contains(999), "body and sender forged refs excluded from authority");
            int valid = allowed.iterator().next();
            AiSummaryPrompt.validateReferences("结论 [m" + valid + "]", allowed);
            assertions++;
            fails(() -> AiSummaryPrompt.validateReferences("伪造 [m999]", allowed), "per-source references cannot cite another input");
        }
        PromptOptions escaped = new PromptOptions(PromptOptions.GENERAL, repeat("\u0001x", 500));
        fails(() -> AiSummaryPrompt.sourceChunks(input, escaped), "escaped requirements exhausting budget fail before HTTP, without silent truncation");
        fails(() -> AiSummaryPrompt.sourcePrompt(repeat("x", 3500), 1, 1, options), "wrong-budget prebuilt chunk rejected");
        fails(() -> AiSummaryPrompt.validateReferences("引用 [m01]", Collections.singleton(1)), "allowed-set validator also rejects noncanonical references");
        check(AiSummaryPrompt.references("来源 [m2][m4][m2]").equals(new java.util.LinkedHashSet<>(Arrays.asList(2, 4))), "references retain global ids and deduplicate");
    }

    private static void multipleMergeRoundsKeepDirection() {
        PromptOptions options = new PromptOptions(PromptOptions.DECISIONS, "关注发布的反对理由，不把建议当决定。");
        List<String> partials = new ArrayList<>();
        for (int i = 1; i <= 18; i++) partials.add(repeat("不同意见仍未解决。", 85) + " [m" + i + "]");
        int rounds = 0;
        while (partials.size() > 1) {
            List<String> groups = AiSummaryPrompt.mergeChunks(partials, options);
            check(groups.size() < partials.size(), "every merge round progresses");
            List<String> next = new ArrayList<>();
            for (String group : groups) {
                String prompt = AiSummaryPrompt.mergePrompt(group, options);
                check(prompt.contains("决策与争议") && prompt.contains(options.customInstructions), "same immutable direction on every merge round");
                check(prompt.length() + AiSummaryPrompt.SYSTEM_PROMPT.length() + AiSummaryPrompt.OUTPUT_RESERVE_CHARACTERS
                        <= AiSummaryPrompt.MAX_REQUEST_CHARACTERS, "merge reserves direction and output");
                java.util.Set<Integer> allowed = AiSummaryPrompt.references(group);
                StringBuilder merged = new StringBuilder("争议未决。");
                for (int ref : allowed) merged.append("[m").append(ref).append(']');
                AiSummaryPrompt.validateReferences(merged.toString(), allowed);
                assertions++;
                fails(() -> AiSummaryPrompt.validateReferences(merged + "[m999]", allowed), "merge cannot introduce absent reference");
                next.add(merged.toString());
            }
            partials = next;
            rounds++;
        }
        check(rounds >= 2, "exercise at least two merge rounds");
        check(AiSummaryPrompt.references(partials.get(0)).size() == 18, "global refs remain unchanged across rounds");
    }

    private static String decodeJsonString(String json) {
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < json.length() - 1; i++) {
            char c = json.charAt(i);
            if (c != '\\') {
                result.append(c);
                continue;
            }
            char escape = json.charAt(++i);
            if (escape == 'u') {
                result.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                i += 4;
            } else if (escape == '"' || escape == '\\' || escape == '/') {
                result.append(escape);
            } else {
                throw new AssertionError("unexpected JSON escape");
            }
        }
        return result.toString();
    }

    private static boolean validSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static String repeat(String text, int times) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) {
            result.append(text);
        }
        return result.toString();
    }

    private static void fails(Runnable action, String reason) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            assertions++;
            return;
        }
        throw new AssertionError(reason);
    }

    private static void check(boolean value, String reason) {
        assertions++;
        if (!value) {
            throw new AssertionError(reason);
        }
    }
}
