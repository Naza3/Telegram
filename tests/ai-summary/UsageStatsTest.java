/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

public final class UsageStatsTest {
    private static int assertions;

    public static void main(String[] args) {
        UsageStats parsed = UsageStats.fromJson(new JSONObject("{\"prompt_tokens\":31,\"completion_tokens\":17,\"total_tokens\":49,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":0}}"));
        check(parsed.promptTokens == 31 && parsed.completionTokens == 17 && parsed.totalTokens == 49 && parsed.reasoningTokens == 0,
                "explicit counters, including non-derived total and actual zero, must survive");
        Object[] invalid = {JSONObject.NULL, "12", true, -1, 1.25, new BigDecimal("2.000000000000001"),
                new BigInteger("9223372036854775808"), new BigDecimal("1e30"), 9_007_199_254_740_992d,
                3.0, 1.0f, new BigDecimal("4.000")};
        for (Object value : invalid) {
            UsageStats usage = UsageStats.fromJson(new JSONObject().put("prompt_tokens", value).put("completion_tokens", 5));
            check(usage.promptTokens == -1 && usage.completionTokens == 5 && usage.totalTokens == -1 && usage.reasoningTokens == -1,
                    "invalid/missing usage must be unknown without discarding independent valid fields: " + value);
        }
        for (Object value : new Object[]{0, 2L, BigInteger.valueOf(Long.MAX_VALUE)}) {
            long expected = ((Number) value).longValue();
            check(UsageStats.fromJson(new JSONObject().put("prompt_tokens", value)).promptTokens == expected, "exact count rejected");
        }
        check(UsageStats.fromJson(null) == UsageStats.UNKNOWN_USAGE, "missing usage became zero");
        check(UsageStats.fromJson(new JSONObject().put("reasoning_tokens", 25)).reasoningTokens == -1,
                "unrecognized reasoning counter must not be guessed");
        UsageStats sum = UsageStats.aggregate(Arrays.asList(parsed, new UsageStats(4, 8, 12, -1)));
        check(sum.promptTokens == 35 && sum.completionTokens == 25 && sum.totalTokens == 61 && sum.reasoningTokens == -1,
                "aggregation must retain per-field uncertainty");
        check(UsageStats.aggregate(Collections.emptyList()) == UsageStats.UNKNOWN_USAGE, "no requests is not measured zero");
        check(UsageStats.aggregate(Arrays.asList(parsed, null)) == UsageStats.UNKNOWN_USAGE, "missing request cannot yield exact total");
        UsageStats overflow = UsageStats.aggregate(Arrays.asList(new UsageStats(Long.MAX_VALUE, 1, 2, 0), new UsageStats(1, 2, 3, 0)));
        check(overflow.promptTokens == -1 && overflow.completionTokens == 3 && overflow.totalTokens == 5 && overflow.reasoningTokens == 0,
                "overflow must not wrap or taint other fields");
        boolean rejected = false;
        try { new UsageStats(-2, 0, 0, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid public count accepted");
        System.out.println("UsageStatsTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
