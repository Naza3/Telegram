/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONObject;

import java.math.BigInteger;
import java.util.List;

/** Server-reported token counts only; unknown values are never estimated from characters. */
public final class UsageStats {
    public static final long UNKNOWN = -1;
    public static final UsageStats UNKNOWN_USAGE = new UsageStats(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    public final long promptTokens, completionTokens, totalTokens, reasoningTokens;

    public UsageStats(long promptTokens, long completionTokens, long totalTokens, long reasoningTokens) {
        if (promptTokens < UNKNOWN || completionTokens < UNKNOWN || totalTokens < UNKNOWN || reasoningTokens < UNKNOWN) {
            throw new IllegalArgumentException("Token counts must be nonnegative or unknown");
        }
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.reasoningTokens = reasoningTokens;
    }

    static UsageStats fromJson(JSONObject usage) {
        if (usage == null) return UNKNOWN_USAGE;
        JSONObject details = usage.optJSONObject("completion_tokens_details");
        return new UsageStats(count(usage.opt("prompt_tokens")), count(usage.opt("completion_tokens")),
                count(usage.opt("total_tokens")), details == null ? UNKNOWN : count(details.opt("reasoning_tokens")));
    }

    private static long count(Object value) {
        if (!(value instanceof Number)) return UNKNOWN;
        long result;
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.signum() < 0 || integer.bitLength() > 63) return UNKNOWN;
            result = integer.longValue();
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            result = ((Number) value).longValue();
        } else return UNKNOWN; // Floating JSON numbers may already have lost their fractional part.
        return result < 0 ? UNKNOWN : result;
    }

    /** Sum independent requests, not repeated cumulative usage events from one stream. */
    public static UsageStats aggregate(List<UsageStats> requests) {
        if (requests == null || requests.isEmpty()) return UNKNOWN_USAGE;
        long prompt = 0, completion = 0, total = 0, reasoning = 0;
        for (UsageStats item : requests) {
            if (item == null) return UNKNOWN_USAGE;
            prompt = add(prompt, item.promptTokens);
            completion = add(completion, item.completionTokens);
            total = add(total, item.totalTokens);
            reasoning = add(reasoning, item.reasoningTokens);
        }
        return new UsageStats(prompt, completion, total, reasoning);
    }

    private static long add(long left, long right) {
        return left == UNKNOWN || right == UNKNOWN || left > Long.MAX_VALUE - right ? UNKNOWN : left + right;
    }
}
