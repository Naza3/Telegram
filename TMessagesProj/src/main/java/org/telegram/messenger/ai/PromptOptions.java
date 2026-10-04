/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.util.Objects;

/** Immutable user core instructions and legacy preference fields captured once per task. */
public final class PromptOptions {
    public static final String GENERAL = "general";
    public static final String PROJECT = "project";
    public static final String DECISIONS = "decisions";
    public static final String TODOS = "todos";
    public static final int MAX_CUSTOM_CODE_POINTS = 1000;
    public static final int TEMPLATE_VERSION = 1;
    public static final int BUILTIN_RULES_VERSION = 6;
    public static final PromptOptions DEFAULT = new PromptOptions(GENERAL, "");

    public final String templateId;
    public final String customInstructions;
    public final int templateVersion;
    public final int builtinRulesVersion;
    /** Legacy session flag; direct-summary instructions come only from customInstructions. */
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
                    throw new IllegalArgumentException("核心总结要求包含无效的 Unicode 字符。");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("核心总结要求包含无效的 Unicode 字符。");
            }
        }
        if (custom.codePointCount(0, custom.length()) > MAX_CUSTOM_CODE_POINTS) {
            throw new IllegalArgumentException("核心总结要求最多 " + MAX_CUSTOM_CODE_POINTS + " 个 Unicode 字符，请缩短后重试。");
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
