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
    private static final String PREFIX = "local_ai_summary_";

    private AiSummarySettings() {
    }

    public static final class Config {
        public final String baseUrl;
        public final String model;
        public final String apiKey;
        public final int maxOutputTokens;

        public Config(String baseUrl, String model, String apiKey) {
            this(baseUrl, model, apiKey, DEFAULT_MAX_OUTPUT_TOKENS);
        }

        public Config(String baseUrl, String model, String apiKey, int maxOutputTokens) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
            this.model = model == null ? "" : model.trim();
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public static Config load(int account) {
        return load(account, UserConfig.getInstance(account).getClientUserId());
    }

    public static Config load(int account, long ownerId) {
        requireOwner(account, ownerId);
        // Also lets the secret store discard stale ciphertext after an account-slot change.
        String apiKey = AiSummarySecretStore.load(account, ownerId);
        SharedPreferences preferences = MessagesController.getMainSettings(account);
        if (!Long.toString(ownerId).equals(preferences.getString(PREFIX + "owner_id", ""))) {
            return new Config(DEFAULT_BASE_URL, DEFAULT_MODEL, "", DEFAULT_MAX_OUTPUT_TOKENS);
        }
        return new Config(preferences.getString(PREFIX + "base_url", DEFAULT_BASE_URL),
                preferences.getString(PREFIX + "model", DEFAULT_MODEL),
                apiKey,
                preferences.getInt(PREFIX + "max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS));
    }

    /** Returns whether the API key was persisted securely (false means session-only storage). */
    public static boolean save(int account, Config config) {
        return save(account, UserConfig.getInstance(account).getClientUserId(), config);
    }

    /** Binds a pending UI operation to the identity that opened that UI. */
    public static boolean save(int account, long ownerId, Config config) {
        requireOwner(account, ownerId);
        String error = validate(config);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        MessagesController.getMainSettings(account).edit()
                .putString(PREFIX + "owner_id", Long.toString(ownerId))
                .putString(PREFIX + "base_url", config.baseUrl)
                .putString(PREFIX + "model", config.model)
                .putInt(PREFIX + "max_output_tokens", config.maxOutputTokens)
                .remove(PREFIX + "api_key")
                .apply();
        return AiSummarySecretStore.save(account, ownerId, config.apiKey);
    }

    private static void requireOwner(int account, long ownerId) {
        if (ownerId <= 0 || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开群聊后配置模型。");
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
