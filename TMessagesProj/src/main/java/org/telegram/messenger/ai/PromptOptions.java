/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.Objects;

/** Immutable summary direction captured once when a task starts. */
public final class PromptOptions {
    public static final String GENERAL = "general";
    public static final String PROJECT = "project";
    public static final String DECISIONS = "decisions";
    public static final String TODOS = "todos";
    public static final int MAX_CUSTOM_CODE_POINTS = 1000;
    public static final int TEMPLATE_VERSION = 1;
    public static final int BUILTIN_RULES_VERSION = 1;
    public static final PromptOptions DEFAULT = new PromptOptions(GENERAL, "");

    public final String templateId;
    public final String customInstructions;
    public final int templateVersion;
    public final int builtinRulesVersion;
    /** Session scope only: emphasize reliable self-related metadata without excluding any source. */
    public final boolean focusSelf;

    public PromptOptions(String templateId, String customInstructions) {
        this(templateId, customInstructions, false);
    }

    private PromptOptions(String templateId, String customInstructions, boolean focusSelf) {
        if (!GENERAL.equals(templateId) && !PROJECT.equals(templateId)
                && !DECISIONS.equals(templateId) && !TODOS.equals(templateId)) {
            throw new IllegalArgumentException("总结模板无效，请重新选择。");
        }
        String custom = customInstructions == null ? "" : customInstructions;
        // Reject unpaired surrogates before transport encoding can silently replace them.
        for (int i = 0; i < custom.length(); i++) {
            char value = custom.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= custom.length() || !Character.isLowSurrogate(custom.charAt(i))) {
                    throw new IllegalArgumentException("补充要求包含无效的 Unicode 字符。");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("补充要求包含无效的 Unicode 字符。");
            }
        }
        if (custom.codePointCount(0, custom.length()) > MAX_CUSTOM_CODE_POINTS) {
            throw new IllegalArgumentException("补充要求最多 " + MAX_CUSTOM_CODE_POINTS + " 个 Unicode 字符，请缩短后重试。");
        }
        this.templateId = templateId;
        this.customInstructions = custom.trim();
        this.templateVersion = TEMPLATE_VERSION;
        this.builtinRulesVersion = BUILTIN_RULES_VERSION;
        this.focusSelf = focusSelf;
    }

    public PromptOptions withFocusSelf(boolean enabled) {
        return enabled == focusSelf ? this : new PromptOptions(templateId, customInstructions, enabled);
    }

    public static String templateLabel(String templateId) {
        switch (templateId) {
            case GENERAL: return "通用总结";
            case PROJECT: return "项目进展";
            case DECISIONS: return "决策与争议";
            case TODOS: return "待办跟进";
            default: throw new IllegalArgumentException("总结模板无效，请重新选择。");
        }
    }

    static String templateInstructions(String templateId) {
        switch (templateId) {
            case GENERAL: return "兼顾主要话题、已确认结论、不同意见与待办，压缩重复讨论。";
            case PROJECT: return "优先梳理项目进展、已完成事项、阻塞、风险和下一步，明确区分计划与实际完成。";
            case DECISIONS: return "优先梳理已确认决定、决策理由、替代方案、反对意见与未决争议；不能把建议写成决定。";
            case TODOS: return "优先梳理明确待办、负责人、截止时间和依赖；保留任务取消或转交，不猜测指派。";
            default: throw new IllegalArgumentException("总结模板无效，请重新选择。");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PromptOptions)) return false;
        PromptOptions value = (PromptOptions) other;
        return templateVersion == value.templateVersion && builtinRulesVersion == value.builtinRulesVersion
                && focusSelf == value.focusSelf
                && templateId.equals(value.templateId) && customInstructions.equals(value.customInstructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, customInstructions, templateVersion, builtinRulesVersion, focusSelf);
    }
}
