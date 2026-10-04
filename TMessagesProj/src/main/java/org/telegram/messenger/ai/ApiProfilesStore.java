/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Named, encrypted connection profiles. All APIs perform storage work and belong on a worker thread. */
public final class ApiProfilesStore {
    public static final int MAX_PROFILES = 20;
    public static final int MAX_NAME_CODE_POINTS = 80;
    public static final int MAX_STORAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PLAINTEXT_BYTES = (MAX_STORAGE_BYTES - 512) / 4 * 3;
    private static final int MAX_FIELD_CHARACTERS = 32768;
    private static final String NAMESPACE = "api_profiles";
    private ApiProfilesStore() { }

    private static final class State {
        String selectedId;
        final ArrayList<AiSummarySettings.Config> profiles;
        State(String selectedId, ArrayList<AiSummarySettings.Config> profiles) {
            this.selectedId = selectedId; this.profiles = profiles;
        }
    }

    /** Contains secrets; render only names/endpoint/model unless the user explicitly opens an editor. */
    public static synchronized List<AiSummarySettings.Config> list(int account, long ownerId) {
        requireOwner(account, ownerId);
        State state = readOrMigrate(account, ownerId);
        requireOwner(account, ownerId);
        return Collections.unmodifiableList(new ArrayList<>(state.profiles));
    }

    /** Empty is an explicit unselected state, not permission to pick another service automatically. */
    public static synchronized String selectedId(int account, long ownerId) {
        requireOwner(account, ownerId);
        String id = readOrMigrate(account, ownerId).selectedId;
        requireOwner(account, ownerId);
        return id;
    }

    public static synchronized AiSummarySettings.Config loadSelected(int account, long ownerId) {
        requireOwner(account, ownerId);
        State state = readOrMigrate(account, ownerId);
        AiSummarySettings.Config result = find(state, state.selectedId);
        if (result == null) throw new IllegalStateException("尚未选择 API 配置，请先在 API 配置列表中选择一个服务。");
        requireOwner(account, ownerId);
        return result;
    }

    public static synchronized AiSummarySettings.Config get(int account, long ownerId, String id) {
        requireOwner(account, ownerId); requireId(id);
        AiSummarySettings.Config result = find(readOrMigrate(account, ownerId), id);
        requireOwner(account, ownerId);
        return result;
    }

    /** Creates a profile without changing selection or any running request snapshot. */
    public static synchronized AiSummarySettings.Config create(int account, long ownerId, String name,
            AiSummarySettings.Config values) {
        requireOwner(account, ownerId); validateName(name); validateValues(values); requireValuesOwner(values, account, ownerId);
        State state = readOrMigrate(account, ownerId);
        if (state.profiles.size() >= MAX_PROFILES) throw new IllegalStateException("最多保存 " + MAX_PROFILES + " 个 API 配置，请先删除不再使用的配置。");
        requireUniqueName(state, null, name);
        AiSummarySettings.Config created = values.boundTo(UUID.randomUUID().toString(), name.trim(), 1, account, ownerId);
        state.profiles.add(created);
        write(account, ownerId, state);
        return created;
    }

    /** Does not change selection. A stale editor cannot overwrite a newer revision or resurrect deletion. */
    public static synchronized AiSummarySettings.Config update(int account, long ownerId, String id,
            long expectedRevision, String name, AiSummarySettings.Config values) {
        requireOwner(account, ownerId); requireId(id); validateName(name); validateValues(values);
        requireValuesOwner(values, account, ownerId);
        if (!values.profileId.isEmpty() && !values.profileId.equals(id)) {
            throw new IllegalArgumentException("编辑内容不属于这份 API 配置，请重新打开设置。");
        }
        State state = readOrMigrate(account, ownerId);
        AiSummarySettings.Config current = requireExisting(state, id, expectedRevision);
        requireUniqueName(state, id, name);
        AiSummarySettings.Config updated = values.boundTo(id, name.trim(), current.profileRevision + 1, account, ownerId);
        state.profiles.set(state.profiles.indexOf(current), updated);
        write(account, ownerId, state);
        return updated;
    }

    public static synchronized void select(int account, long ownerId, String id) {
        requireOwner(account, ownerId); requireId(id);
        State state = readOrMigrate(account, ownerId);
        if (find(state, id) == null) throw new IllegalStateException("这份 API 配置已删除，请刷新列表。");
        if (state.selectedId.equals(id)) return;
        state.selectedId = id;
        write(account, ownerId, state);
    }

    /** Deleting the current service deliberately leaves no selected service. No failover. */
    public static synchronized void delete(int account, long ownerId, String id, long expectedRevision) {
        requireOwner(account, ownerId); requireId(id);
        State state = readOrMigrate(account, ownerId);
        AiSummarySettings.Config existing = requireExisting(state, id, expectedRevision);
        state.profiles.remove(existing);
        if (state.selectedId.equals(id)) state.selectedId = "";
        // Persist an empty library, preventing a later read from re-importing deleted legacy settings.
        write(account, ownerId, state);
    }

    /** Compatibility only for a first save into a truly uninitialized account. */
    static synchronized AiSummarySettings.Config saveInitial(int account, long ownerId, AiSummarySettings.Config values) {
        requireOwner(account, ownerId); validateValues(values); requireValuesOwner(values, account, ownerId);
        if (readStored(account, ownerId) != null || AiSummarySettings.hasLegacyConfiguration(account, ownerId)) {
            throw new IllegalStateException("请重新打开要编辑的 API 配置后保存，避免覆盖已切换的服务。");
        }
        AiSummarySettings.Config profile = values.boundTo(UUID.randomUUID().toString(), "默认配置", 1, account, ownerId);
        ArrayList<AiSummarySettings.Config> profiles = new ArrayList<>(); profiles.add(profile);
        write(account, ownerId, new State(profile.profileId, profiles));
        return profile;
    }

    /** Logout can arrive after slot reuse; only this old owner is deleted. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        RuntimeException failure = null;
        try { SummaryPrivateStorage.delete(NAMESPACE, account, ownerId); }
        catch (RuntimeException error) { failure = error; }
        AiSummarySettings.clearLegacyOwner(account, ownerId);
        if (failure != null) throw failure;
    }

    public static void validateName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("请填写 API 配置名称。");
        requireUnicode(name);
        if (name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("API 配置名称最多 " + MAX_NAME_CODE_POINTS + " 个 Unicode 字符。");
        }
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) {
            throw new IllegalArgumentException("API 配置名称不能包含换行或控制字符。");
        }
    }

    private static State readOrMigrate(int account, long ownerId) {
        State state = readStored(account, ownerId);
        if (state != null) {
            // Retry a best-effort cleanup that failed after a previous durable migration.
            AiSummarySettings.clearLegacyOwner(account, ownerId);
            return state;
        }
        boolean hadLegacy = AiSummarySettings.hasLegacyConfiguration(account, ownerId);
        AiSummarySettings.Config legacy = AiSummarySettings.loadLegacyForMigration(account, ownerId);
        validateValues(legacy);
        AiSummarySettings.Config first = legacy.boundTo(UUID.randomUUID().toString(), hadLegacy ? "原有配置" : "默认配置",
                1, account, ownerId);
        ArrayList<AiSummarySettings.Config> profiles = new ArrayList<>(); profiles.add(first);
        state = new State(first.profileId, profiles);
        // The old config/key are untouched until encryption + disk publication have succeeded.
        write(account, ownerId, state);
        AiSummarySettings.clearLegacyOwner(account, ownerId);
        return state;
    }

    private static State readStored(int account, long ownerId) {
        requireOwner(account, ownerId);
        String plain = SummaryPrivateStorage.read(NAMESPACE, account, ownerId, MAX_STORAGE_BYTES);
        requireOwner(account, ownerId);
        if (plain == null) return null;
        if (plain.getBytes(StandardCharsets.UTF_8).length > MAX_PLAINTEXT_BYTES) throw corrupt();
        try {
            JSONObject root = new JSONObject(plain);
            if (root.getInt("version") != 1) throw corrupt();
            String selected = root.getString("selected_id");
            if (!selected.isEmpty()) requireId(selected);
            JSONArray entries = root.getJSONArray("profiles");
            if (entries.length() > MAX_PROFILES) throw corrupt();
            ArrayList<AiSummarySettings.Config> profiles = new ArrayList<>();
            HashSet<String> ids = new HashSet<>(), names = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject value = entries.getJSONObject(i);
                String id = value.getString("id"), name = value.getString("name");
                long revision = value.getLong("revision");
                requireId(id); validateName(name);
                if (revision <= 0 || revision == Long.MAX_VALUE || !name.equals(name.trim())) throw corrupt();
                AiSummarySettings.ServiceType type = AiSummarySettings.ServiceType.valueOf(value.getString("service_type"));
                AiSummarySettings.Config config = new AiSummarySettings.Config(value.getString("base_url"), value.getString("model"),
                        value.getString("api_key"), value.getInt("max_output_tokens"), value.getBoolean("stream"),
                        value.getInt("input_character_budget"), type).boundTo(id, name, revision, account, ownerId);
                validateValues(config);
                if (!ids.add(id) || !names.add(normalizedName(name))) throw corrupt();
                profiles.add(config);
            }
            if (!selected.isEmpty() && !ids.contains(selected)) throw corrupt();
            return new State(selected, profiles);
        } catch (JSONException | IllegalArgumentException error) {
            // Do not include JSON, profile fields, URLs or secrets in a diagnostic/cause.
            throw corrupt();
        }
    }

    private static void write(int account, long ownerId, State state) {
        requireOwner(account, ownerId);
        try {
            JSONArray profiles = new JSONArray();
            for (AiSummarySettings.Config value : state.profiles) {
                profiles.put(new JSONObject().put("id", value.profileId).put("name", value.profileName)
                        .put("revision", value.profileRevision).put("base_url", value.baseUrl).put("model", value.model)
                        .put("api_key", value.apiKey).put("max_output_tokens", value.maxOutputTokens)
                        .put("stream", value.stream).put("input_character_budget", value.inputCharacterBudget)
                        .put("service_type", value.serviceType.name()));
            }
            String plaintext = new JSONObject().put("version", 1).put("selected_id", state.selectedId)
                    .put("profiles", profiles).toString();
            if (state.profiles.size() > MAX_PROFILES || plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_PLAINTEXT_BYTES) {
                throw new IllegalStateException("API 配置超过保存容量，原配置未修改。");
            }
            requireOwner(account, ownerId);
            SummaryPrivateStorage.write(NAMESPACE, account, ownerId, plaintext, MAX_STORAGE_BYTES);
            requireOwner(account, ownerId);
        } catch (JSONException impossible) {
            throw new IllegalStateException("无法编码 API 配置，原配置未修改。");
        }
    }

    private static AiSummarySettings.Config find(State state, String id) {
        for (AiSummarySettings.Config profile : state.profiles) if (profile.profileId.equals(id)) return profile;
        return null;
    }
    private static AiSummarySettings.Config requireExisting(State state, String id, long revision) {
        AiSummarySettings.Config current = find(state, id);
        if (current == null) throw new IllegalStateException("这份 API 配置已删除，未恢复旧内容。请刷新列表。");
        if (revision <= 0 || current.profileRevision != revision || revision >= Long.MAX_VALUE - 1) {
            throw new IllegalStateException("这份 API 配置已被修改，请重新打开后编辑。");
        }
        return current;
    }
    private static void requireUniqueName(State state, String exceptId, String name) {
        String normalized = normalizedName(name);
        for (AiSummarySettings.Config config : state.profiles) {
            if (!config.profileId.equals(exceptId) && normalizedName(config.profileName).equals(normalized)) {
                throw new IllegalArgumentException("已有同名 API 配置，请更换名称。");
            }
        }
    }
    private static String normalizedName(String name) {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static void validateValues(AiSummarySettings.Config config) {
        String message = AiSummarySettings.validate(config);
        if (message != null) throw new IllegalArgumentException(message);
        if (config.baseUrl.length() > MAX_FIELD_CHARACTERS || config.apiKey.length() > MAX_FIELD_CHARACTERS) {
            throw new IllegalArgumentException("API 地址或密钥超过安全保存长度，请检查配置。");
        }
        requireUnicode(config.baseUrl); requireUnicode(config.model); requireUnicode(config.apiKey);
    }
    private static void requireValuesOwner(AiSummarySettings.Config config, int account, long ownerId) {
        if (!config.profileId.isEmpty() && (config.profileAccount != account || config.profileOwnerId != ownerId)) {
            throw new IllegalStateException("API 配置属于其他账号，请重新打开设置。");
        }
    }
    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("API 配置包含无效的 Unicode 字符。");
                }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("API 配置包含无效的 Unicode 字符。");
        }
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("API 配置标识无效。");
    }
    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开 API 配置。");
        }
    }
    private static IllegalStateException corrupt() {
        return new IllegalStateException("API 配置记录无法读取，原记录未被修改；未自动切换到其他服务。");
    }
}
