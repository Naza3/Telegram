package org.telegram.messenger.ai;

import org.telegram.messenger.UserConfig;
import java.util.Arrays;
import java.util.Collections;

/** Snapshot identity, explicit-only access, invalidation and bounded retained memory. */
public final class SummaryResultCacheTest {
    private static int assertions;
    private static final PromptOptions OPTIONS = PromptOptions.DEFAULT;
    private static final AiSummarySettings.Config CONFIG = new AiSummarySettings.Config("http://127.0.0.1:8080/v1", "mnn-local", "private-key", 512, false, 6000);
    public static void main(String[] args) {
        UserConfig.getInstance(0).setClientUserId(1000);
        UserConfig.getInstance(1).setClientUserId(1001);
        identity(); explicitAndInvalidation(); bounds();
        System.out.println("SummaryResultCacheTest: " + assertions + " assertions passed");
    }
    private static void identity() {
        SummaryMessage original = message(1, "原文", 0);
        SummaryResultCache.Key key = key(0, 1000, -10, 0, original, OPTIONS, CONFIG, "");
        check(key.equals(key(0, 1000, -10, 0, original, OPTIONS, CONFIG, "")), "same immutable snapshot yields same opaque key");
        check(!key.toString().contains("private-key") && !key.toString().contains("原文"), "key display exposes neither source nor secret");
        check(!key.equals(key(1, 1001, -10, 0, original, OPTIONS, CONFIG, "")), "account and real owner isolated");
        check(!key.equals(key(0, 2000, -10, 0, original, OPTIONS, CONFIG, "")), "slot reuse changes key");
        check(!key.equals(key(0, 1000, -11, 0, original, OPTIONS, CONFIG, "")), "dialog scope included");
        check(!key.equals(key(0, 1000, -10, 42, original, OPTIONS, CONFIG, "")), "topic scope included");
        check(!key.equals(key(0, 1000, -10, 0, message(1, "改文", 0), OPTIONS, CONFIG, "")), "text changes key even without edit timestamp");
        check(!key.equals(key(0, 1000, -10, 0, message(1, "原文", 1), OPTIONS, CONFIG, "")), "edit timestamp included");
        SummaryMessage metadata = new SummaryMessage(-10, 1, 1700000001, "sender", "原文", 55, 8, -10, true, false, 0, true, true);
        check(!key.equals(key(0, 1000, -10, 0, metadata, OPTIONS, CONFIG, "")), "reply and self metadata included");
        check(!key.equals(key(0, 1000, -10, 0, original, new PromptOptions(PromptOptions.PROJECT, ""), CONFIG, "")), "template included");
        check(!key.equals(key(0, 1000, -10, 0, original, new PromptOptions(PromptOptions.GENERAL, "关注风险"), CONFIG, "")), "custom instructions included");
        check(!key.equals(key(0, 1000, -10, 0, original, OPTIONS.withFocusSelf(true), CONFIG, "")), "session self emphasis included");
        for (AiSummarySettings.Config changed : Arrays.asList(
                new AiSummarySettings.Config("http://127.0.0.1:8081/v1", "mnn-local", "private-key", 512, false, 6000),
                new AiSummarySettings.Config(CONFIG.baseUrl, "other-model", "private-key", 512, false, 6000),
                new AiSummarySettings.Config(CONFIG.baseUrl, CONFIG.model, "different-key", 512, false, 6000),
                new AiSummarySettings.Config(CONFIG.baseUrl, CONFIG.model, CONFIG.apiKey, 256, false, 6000),
                new AiSummarySettings.Config(CONFIG.baseUrl, CONFIG.model, CONFIG.apiKey, 512, true, 6000),
                new AiSummarySettings.Config(CONFIG.baseUrl, CONFIG.model, CONFIG.apiKey, 512, false, 12000))) {
            check(!key.equals(key(0, 1000, -10, 0, original, OPTIONS, changed, "")), "all API/generation configuration included");
        }
        check(!key.equals(key(0, 1000, -10, 0, original, OPTIONS, CONFIG, "actual-model-v2")), "verified model version included when available");
    }
    private static void explicitAndInvalidation() {
        SummaryResultCache cache = new SummaryResultCache(8, 16384);
        SummaryResultCache.Key key = key(0, 1000, -10, 0, message(1, "原文", 0), OPTIONS, CONFIG, "");
        check(cache.put(key, "【话题】讨论发布。【结论】尚未决定。【待办】无明确事项。", 1234), "completed citation-free result stored");
        check(cache.get(key, false) == null, "normal/regeneration path never reuses unknown model alias");
        check(cache.get(key, true).generatedAtMillis == 1234, "explicit history shows original generation time");
        check(cache.put(key, "无引用编号的完整输出", 1235), "citation-free result must remain available to explicit history");
        check("无引用编号的完整输出".equals(cache.get(key, true).summary), "cached plain summary changed");
        check(!cache.put(key, " \n\t ", 1236) && !cache.put(key, null, 1236), "empty or null result not stored");
        check("无引用编号的完整输出".equals(cache.get(key, true).summary), "rejected empty output replaced completed history");
        UserConfig.getInstance(0).setClientUserId(2000);
        check(cache.get(key, true) == null && !cache.put(key, "旧账号迟到结果 [m1]", 1235), "slot switch blocks stale cache reads and writes");
        cache.clearOwner(0, 1000);
        UserConfig.getInstance(0).setClientUserId(1000);
        check(cache.get(key, true) == null, "logout removes owner history");
        cache.put(key, "摘要 [m1]", 1234);
        SummaryResultCache.Key topic = key(0, 1000, -10, 42, message(2, "话题原文", 0), OPTIONS, CONFIG, "");
        cache.put(topic, "话题摘要 [m1]", 1234);
        cache.invalidateMessage(0, 1000, -10, 1);
        check(cache.get(key, true) == null && cache.get(topic, true) != null, "edit/deletion invalidates only results using affected source");
        cache.clearScope(0, 1000, -10, 42);
        check(cache.size() == 0 && cache.retainedBytes() == 0, "topic permission/scope invalidation releases all matching entries");
    }
    private static void bounds() {
        SummaryResultCache cache = new SummaryResultCache(2, 16384);
        SummaryResultCache.Key a = key(0, 1000, -10, 0, message(1, "a", 0), OPTIONS, CONFIG, "");
        SummaryResultCache.Key b = key(0, 1000, -10, 0, message(2, "b", 0), OPTIONS, CONFIG, "");
        SummaryResultCache.Key c = key(0, 1000, -10, 0, message(3, "c", 0), OPTIONS, CONFIG, "");
        cache.put(a, "a [m1]", 1); cache.put(b, "b [m1]", 2); cache.get(a, true); cache.put(c, "c [m1]", 3);
        check(cache.size() == 2 && cache.get(b, true) == null && cache.get(a, true) != null, "LRU count bound respected");
        SummaryResultCache tiny = new SummaryResultCache(8, 1024);
        check(!tiny.put(a, repeat("大", 500) + " [m1]", 1), "oversized individual result rejected");
        tiny.put(a, "a [m1]", 1); tiny.put(b, "b [m1]", 2);
        check(tiny.retainedBytes() <= 1024 && tiny.size() == 1, "retained byte bound evicts old result");
        tiny.clear(); check(tiny.size() == 0 && tiny.retainedBytes() == 0, "clear releases accounting");
    }
    private static SummaryResultCache.Key key(int account, long owner, long dialog, long topic, SummaryMessage message, PromptOptions options, AiSummarySettings.Config config, String version) {
        return SummaryResultCache.key(account, owner, dialog, topic, Collections.singletonList(message), options, config, version);
    }
    private static SummaryMessage message(int id, String text, int edit) { return new SummaryMessage(-10, id, 1700000000 + id, "sender", text, 55, 0, 0, false, false, edit, false, false); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
}
