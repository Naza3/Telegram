/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.net.URI;
import java.net.URISyntaxException;

public final class AiSummarySettings {
    // MNN Chat's default service address; the service screen remains authoritative.
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080/v1";
    public static final String DEFAULT_MODEL = "mnn-local";
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;
    /** Conservative character estimate, not the model tokenizer's context limit. */
    public static final int DEFAULT_INPUT_CHARACTER_BUDGET = 6000;
    public static final int MAX_INPUT_CHARACTER_BUDGET = 64000;
    public enum ServiceType { GENERIC, MNN_LOCAL }
    private static final String PREFIX = "local_ai_summary_";

    private AiSummarySettings() {
    }

    public static final class Config {
        public final String baseUrl;
        public final String model;
        public final String apiKey;
        public final int maxOutputTokens;
        public final boolean stream;
        public final int inputCharacterBudget;
        /** Explicit user choice; never inferred from a loopback address or model alias. */
        public final ServiceType serviceType;
        /** Immutable profile identity captured with these values; legacy constructors remain unbound. */
        public final String profileId, profileName;
        public final long profileRevision, profileOwnerId;
        public final int profileAccount;

        public Config(String baseUrl, String model, String apiKey) {
            this(baseUrl, model, apiKey, DEFAULT_MAX_OUTPUT_TOKENS);
        }

        public Config(String baseUrl, String model, String apiKey, int maxOutputTokens) {
            this(baseUrl, model, apiKey, maxOutputTokens, false);
        }

        public Config(String baseUrl, String model, String apiKey, int maxOutputTokens, boolean stream) {
            this(baseUrl, model, apiKey, maxOutputTokens, stream, DEFAULT_INPUT_CHARACTER_BUDGET);
        }

        public Config(String baseUrl, String model, String apiKey, int maxOutputTokens,
                      boolean stream, int inputCharacterBudget) {
            this(baseUrl, model, apiKey, maxOutputTokens, stream, inputCharacterBudget, ServiceType.GENERIC);
        }

        public Config(String baseUrl, String model, String apiKey, int maxOutputTokens,
                      boolean stream, int inputCharacterBudget, ServiceType serviceType) {
            this(baseUrl, model, apiKey, maxOutputTokens, stream, inputCharacterBudget, serviceType,
                    "", "", 0, -1, 0);
        }

        private Config(String baseUrl, String model, String apiKey, int maxOutputTokens,
                boolean stream, int inputCharacterBudget, ServiceType serviceType, String profileId,
                String profileName, long profileRevision, int profileAccount, long profileOwnerId) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
            this.model = model == null ? "" : model.trim();
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.maxOutputTokens = maxOutputTokens;
            this.stream = stream;
            this.inputCharacterBudget = inputCharacterBudget;
            this.serviceType = serviceType;
            this.profileId = profileId; this.profileName = profileName; this.profileRevision = profileRevision;
            this.profileAccount = profileAccount; this.profileOwnerId = profileOwnerId;
        }

        /** A retry, probe or editor can change values without accidentally changing the target profile. */
        public Config withValues(String baseUrl, String model, String apiKey, int maxOutputTokens,
                boolean stream, int inputCharacterBudget, ServiceType serviceType) {
            return new Config(baseUrl, model, apiKey, maxOutputTokens, stream, inputCharacterBudget, serviceType,
                    profileId, profileName, profileRevision, profileAccount, profileOwnerId);
        }

        Config boundTo(String id, String name, long revision, int account, long ownerId) {
            return new Config(baseUrl, model, apiKey, maxOutputTokens, stream, inputCharacterBudget, serviceType,
                    id, name, revision, account, ownerId);
        }
    }

    public static Config load(int account) {
        return load(account, UserConfig.getInstance(account).getClientUserId());
    }

    /** Worker-thread API. No implicit fallback when the selected profile was removed or is unreadable. */
    public static Config load(int account, long ownerId) {
        return ApiProfilesStore.loadSelected(account, ownerId);
    }

    /** Returns true only after encrypted profile storage confirms the write. */
    public static boolean save(int account, Config config) {
        return save(account, UserConfig.getInstance(account).getClientUserId(), config);
    }

    /** Updates the editor's captured profile, never whichever service was selected meanwhile. */
    public static boolean save(int account, long ownerId, Config config) {
        requireOwner(account, ownerId);
        if (config == null) throw new IllegalArgumentException("没有可保存的 API 配置。");
        if (config.profileId.isEmpty()) ApiProfilesStore.saveInitial(account, ownerId, config);
        else {
            if (config.profileAccount != account || config.profileOwnerId != ownerId) {
                throw new IllegalStateException("API 配置属于其他账号，请重新打开设置。");
            }
            ApiProfilesStore.update(account, ownerId, config.profileId, config.profileRevision,
                    config.profileName, config);
        }
        return true;
    }

    static boolean hasLegacyConfiguration(int account, long ownerId) {
        return Long.toString(ownerId).equals(MessagesController.getMainSettings(account)
                .getString(PREFIX + "owner_id", ""));
    }

    /** Strict migration: never turn an unreadable old secret into an apparently valid empty key. */
    static Config loadLegacyForMigration(int account, long ownerId) {
        requireOwner(account, ownerId);
        if (!hasLegacyConfiguration(account, ownerId)) {
            return new Config(DEFAULT_BASE_URL, DEFAULT_MODEL, "", DEFAULT_MAX_OUTPUT_TOKENS);
        }
        SharedPreferences preferences = MessagesController.getMainSettings(account);
        Config config = new Config(preferences.getString(PREFIX + "base_url", DEFAULT_BASE_URL),
                preferences.getString(PREFIX + "model", DEFAULT_MODEL),
                AiSummarySecretStore.loadForMigration(account, ownerId),
                preferences.getInt(PREFIX + "max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS),
                preferences.getInt(PREFIX + "stream", 0) == 1,
                preferences.getInt(PREFIX + "input_character_budget", DEFAULT_INPUT_CHARACTER_BUDGET),
                preferences.getInt(PREFIX + "service_type", 0) == 1 ? ServiceType.MNN_LOCAL : ServiceType.GENERIC);
        requireOwner(account, ownerId);
        return config;
    }

    /** Called only after durable migration (or logout); never removes another owner's legacy state. */
    static void clearLegacyOwner(int account, long ownerId) {
        if (!hasLegacyConfiguration(account, ownerId)) return;
        if (!AiSummarySecretStore.clearOwner(account, ownerId)) return;
        SharedPreferences preferences = MessagesController.getMainSettings(account);
        if (!Long.toString(ownerId).equals(preferences.getString(PREFIX + "owner_id", ""))) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String suffix : new String[] {"owner_id", "base_url", "model", "max_output_tokens", "stream",
                "input_character_budget", "service_type", "api_key"}) editor.remove(PREFIX + suffix);
        editor.commit();
    }

    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开 AI 总结后配置模型。");
        }
    }

    /** Returns null when configuration is usable, otherwise a user-facing error. */
    public static String validate(Config config) {
        if (config == null || config.baseUrl.isEmpty()) {
            return "请填写模型 API 地址。";
        }
        try {
            URI uri = new URI(config.baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                return "API 地址必须是有效的 http:// 或 https:// 地址。";
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return "API 地址不能包含用户名、密码、查询参数或 # 片段；密钥请填在 API Key 中。";
            }
        } catch (URISyntaxException e) {
            return "API 地址格式无效。";
        }
        if (config.model.length() > 256 || containsControl(config.model)) {
            return "模型名称过长或包含无效字符。";
        }
        if (config.maxOutputTokens < 64 || config.maxOutputTokens > 8192) {
            return "最大输出 tokens 必须在 64 到 8192 之间。";
        }
        if (config.serviceType == null) return "请选择有效的模型服务类型。";
        if (config.serviceType == ServiceType.MNN_LOCAL && config.maxOutputTokens > 2048) {
            return "MNN 本机 API（localapi.5）的最大输出为 2048 tokens，请降低此值；更大的输出需要服务端支持。";
        }
        if (config.inputCharacterBudget < 2048 || config.inputCharacterBudget > MAX_INPUT_CHARACTER_BUDGET) {
            return "上下文字符预算须在 2048–" + MAX_INPUT_CHARACTER_BUDGET + " 之间。这是保守估算，不是精确 token 数。";
        }
        if (containsControl(config.apiKey)) {
            return "API Key 不能包含换行或控制字符。";
        }
        return null;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static String completionUrl(Config config) {
        String base = config.baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        String path = URI.create(base).getPath();
        return base + (path == null || path.isEmpty() ? "/v1/chat/completions" : "/chat/completions");
    }
}
