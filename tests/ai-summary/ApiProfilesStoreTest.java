/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.List;

/** Real store + JVM AES-GCM boundary; Android Keystore and atomic disk behaviour need device coverage. */
public final class ApiProfilesStoreTest {
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000;
    private static int cases, assertions;
    private static final String NAMESPACE = "api_profiles";
    public static void main(String[] args) throws Exception {
        run("fresh account gets one identity-bound default", ApiProfilesStoreTest::fresh);
        run("legacy metadata and secret migrate exactly once", ApiProfilesStoreTest::migration);
        run("failed migration keeps old metadata and key", ApiProfilesStoreTest::migrationFailure);
        run("cleanup failure retries without re-importing", ApiProfilesStoreTest::cleanupFailure);
        run("create select and delete never implicitly fail over", ApiProfilesStoreTest::selection);
        run("stale editors cannot overwrite or resurrect", ApiProfilesStoreTest::revision);
        run("settings facade updates captured identity after selection changes", ApiProfilesStoreTest::facade);
        run("immutable request snapshot survives profile switch edit and delete", ApiProfilesStoreTest::snapshot);
        run("owner and slot isolation including late logout", ApiProfilesStoreTest::owners);
        run("Unicode names limits capacity and duplicate protection", ApiProfilesStoreTest::validation);
        run("encrypted storage failures do not select or replace", ApiProfilesStoreTest::storageFailures);
        run("malformed data and unknown selection are fail closed", ApiProfilesStoreTest::corruption);
        System.out.println("ApiProfilesStoreTest: " + cases + " cases passed, " + assertions + " assertions");
    }
    private static void fresh() {
        reset(); List<AiSummarySettings.Config> list = all();
        check(list.size() == 1, "fresh store did not get exactly one default");
        AiSummarySettings.Config first = list.get(0);
        check(!first.profileId.isEmpty() && first.profileRevision == 1 && first.profileAccount == ACCOUNT
                && first.profileOwnerId == OWNER && first.profileName.equals("默认配置"), "identity missing");
        check(first.apiKey.isEmpty() && first.serviceType == AiSummarySettings.ServiceType.GENERIC,
                "default leaked a key or inferred a service type");
        check(first.profileId.equals(selected().profileId) && first.profileId.equals(ApiProfilesStore.selectedId(ACCOUNT, OWNER)),
                "first selection not stable");
        expect(() -> list.clear());
        expect(() -> ApiProfilesStore.list(-1, OWNER)); expect(() -> ApiProfilesStore.list(ACCOUNT, 0));
    }
    private static void migration() {
        reset(); legacy("legacy-secret", AiSummarySettings.ServiceType.MNN_LOCAL, 2000);
        String id = selected().profileId;
        AiSummarySettings.Config first = selected();
        check(first.profileName.equals("原有配置") && first.baseUrl.equals("https://example.invalid/custom/v1")
                && first.model.equals("legacy-model") && first.apiKey.equals("legacy-secret") && first.stream
                && first.inputCharacterBudget == 32000 && first.maxOutputTokens == 2000
                && first.serviceType == AiSummarySettings.ServiceType.MNN_LOCAL, "migration lost a configured field");
        check(first.profileId.equals(id) && all().size() == 1, "migration duplicated first profile");
        check(!AiSummarySettings.hasLegacyConfiguration(ACCOUNT, OWNER) && AiSummarySecretStore.load(ACCOUNT, OWNER).isEmpty(),
                "durable migration kept legacy credentials active");
        String disk = disk();
        check(!disk.contains("legacy-secret") && !disk.contains("legacy-model") && !disk.contains("example.invalid"),
                "profile or credential written in plaintext");
        check(!MessagesController.getMainSettings(ACCOUNT).values.containsValue("legacy-secret"), "key copied to preferences");
    }
    private static void migrationFailure() {
        reset(); legacy("recoverable-secret", AiSummarySettings.ServiceType.GENERIC, 4096);
        AiSummarySecretStore.failMigrationReads = 1;
        expect(ApiProfilesStoreTest::selected);
        check(disk() == null && AiSummarySettings.hasLegacyConfiguration(ACCOUNT, OWNER)
                && AiSummarySecretStore.load(ACCOUNT, OWNER).equals("recoverable-secret"), "secret read failure destroyed original");
        SummaryPrivateStorage.failWrites = 1;
        expect(ApiProfilesStoreTest::selected);
        check(disk() == null && AiSummarySettings.hasLegacyConfiguration(ACCOUNT, OWNER)
                && AiSummarySecretStore.load(ACCOUNT, OWNER).equals("recoverable-secret"), "failed encryption/storage cleared original");
        check(selected().apiKey.equals("recoverable-secret"), "migration retry lost original key");
    }
    private static void cleanupFailure() {
        reset(); legacy("original-secret", AiSummarySettings.ServiceType.GENERIC, 1024);
        AiSummarySecretStore.failClears = 1;
        AiSummarySettings.Config first = selected();
        check(AiSummarySettings.hasLegacyConfiguration(ACCOUNT, OWNER), "fixture cleanup did not fail");
        AiSummarySettings.Config changed = ApiProfilesStore.update(ACCOUNT, OWNER, first.profileId, first.profileRevision,
                "edited", first.withValues(first.baseUrl, "edited-model", "edited-secret", 512, false, 6000, first.serviceType));
        check(selected().profileId.equals(changed.profileId) && selected().apiKey.equals("edited-secret"), "cleanup retry re-imported old key");
        check(!AiSummarySettings.hasLegacyConfiguration(ACCOUNT, OWNER) && all().size() == 1, "cleanup not retried");
    }
    private static void selection() {
        reset(); AiSummarySettings.Config first = selected();
        AiSummarySettings.Config other = ApiProfilesStore.create(ACCOUNT, OWNER, "另一个服务", values("other", "other-key"));
        check(selected().profileId.equals(first.profileId), "create switched active service");
        ApiProfilesStore.select(ACCOUNT, OWNER, other.profileId);
        check(selected().profileId.equals(other.profileId), "explicit selection not saved");
        ApiProfilesStore.delete(ACCOUNT, OWNER, other.profileId, other.profileRevision);
        check(ApiProfilesStore.selectedId(ACCOUNT, OWNER).isEmpty(), "delete silently selected a fallback");
        expect(ApiProfilesStoreTest::selected);
        check(all().size() == 1, "deleting selected removed other profiles");
        ApiProfilesStore.delete(ACCOUNT, OWNER, first.profileId, first.profileRevision);
        check(all().isEmpty(), "empty library regenerated defaults");
        expect(ApiProfilesStoreTest::selected);
        AiSummarySettings.Config newProfile = ApiProfilesStore.create(ACCOUNT, OWNER, "重新创建", values("new", ""));
        check(ApiProfilesStore.selectedId(ACCOUNT, OWNER).isEmpty(), "empty-store create implicitly selected");
        ApiProfilesStore.select(ACCOUNT, OWNER, newProfile.profileId);
        check(selected().profileId.equals(newProfile.profileId), "explicit selection from empty failed");
        expect(() -> ApiProfilesStore.select(ACCOUNT, OWNER, other.profileId));
    }
    private static void revision() {
        reset(); AiSummarySettings.Config first = selected();
        AiSummarySettings.Config updated = ApiProfilesStore.update(ACCOUNT, OWNER, first.profileId, first.profileRevision,
                "重命名", first.withValues(first.baseUrl, "new-model", "key", 1024, true, 12000, first.serviceType));
        check(updated.profileRevision == 2 && updated.profileId.equals(first.profileId), "revision/identity incorrect");
        expect(() -> ApiProfilesStore.update(ACCOUNT, OWNER, first.profileId, first.profileRevision, "stale", first));
        expect(() -> ApiProfilesStore.delete(ACCOUNT, OWNER, first.profileId, first.profileRevision));
        check(selected().model.equals("new-model"), "stale edit/delete changed live entry");
        ApiProfilesStore.delete(ACCOUNT, OWNER, updated.profileId, updated.profileRevision);
        expect(() -> ApiProfilesStore.update(ACCOUNT, OWNER, updated.profileId, updated.profileRevision, "resurrect", updated));
        check(all().isEmpty(), "deleted entry resurrected");
    }
    private static void facade() {
        reset();
        check(AiSummarySettings.save(ACCOUNT, OWNER, values("initial", "first-key")), "initial compatibility save failed");
        AiSummarySettings.Config editing = selected();
        AiSummarySettings.Config other = ApiProfilesStore.create(ACCOUNT, OWNER, "second", values("second", "second-key"));
        ApiProfilesStore.select(ACCOUNT, OWNER, other.profileId);
        AiSummarySettings.Config edited = editing.withValues(editing.baseUrl, "edited-first", "updated-key", 1024, true, 16000,
                AiSummarySettings.ServiceType.GENERIC);
        check(AiSummarySettings.save(ACCOUNT, OWNER, edited), "bound save failed");
        check(selected().profileId.equals(other.profileId) && selected().apiKey.equals("second-key"), "old editor overwrote selected other service");
        check(ApiProfilesStore.get(ACCOUNT, OWNER, editing.profileId).model.equals("edited-first"), "old editor failed to update its original target");
        expect(() -> AiSummarySettings.save(ACCOUNT, OWNER, edited));
        expect(() -> AiSummarySettings.save(ACCOUNT, OWNER, values("unbound", "unknown")));
        check(selected().model.equals("second"), "unbound compatibility save overwrote current profile");
    }
    private static void snapshot() {
        reset(); AiSummarySettings.Config first = selected();
        AiSummarySettings.Config ready = ApiProfilesStore.update(ACCOUNT, OWNER, first.profileId, first.profileRevision,
                "captured", values("captured-model", "captured-key"));
        AiSummarySettings.Config other = ApiProfilesStore.create(ACCOUNT, OWNER, "other", values("other-model", "other-key"));
        ApiProfilesStore.select(ACCOUNT, OWNER, other.profileId);
        ApiProfilesStore.delete(ACCOUNT, OWNER, ready.profileId, ready.profileRevision);
        check(ready.model.equals("captured-model") && ready.apiKey.equals("captured-key") && ready.profileName.equals("captured"),
                "request snapshot followed new active selection");
        AiSummarySettings.Config retry = ready.withValues(ready.baseUrl, ready.model, ready.apiKey, 512, false, 8000, ready.serviceType);
        check(retry.profileId.equals(ready.profileId) && retry.profileRevision == ready.profileRevision
                && retry.profileOwnerId == OWNER && retry.profileAccount == ACCOUNT, "retry copy lost profile identity");
    }
    private static void owners() {
        reset(); AiSummarySettings.Config old = selected();
        UserConfig.getInstance(ACCOUNT).setClientUserId(2000);
        expect(ApiProfilesStoreTest::selected);
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "stale", values("old", "old-key")));
        AiSummarySettings.Config fresh = AiSummarySettings.load(ACCOUNT, 2000);
        check(fresh.apiKey.isEmpty() && !fresh.profileId.equals(old.profileId), "slot reuse inherited profile");
        expect(() -> AiSummarySettings.save(ACCOUNT, 2000, old));
        expect(() -> ApiProfilesStore.create(ACCOUNT, 2000, "copy other owner", old));
        ApiProfilesStore.clearOwner(ACCOUNT, OWNER);
        check(AiSummarySettings.load(ACCOUNT, 2000).profileId.equals(fresh.profileId), "late logout deleted new owner");
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        check(!selected().profileId.equals(old.profileId), "old-owner cleanup left credentials");
    }
    private static void validation() {
        reset(); selected();
        ApiProfilesStore.create(ACCOUNT, OWNER, "ＡＰＩ", values("model", "key"));
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "api", values("model", "key")));
        for (String name : new String[]{"", " ", "a\nb", "\ud800", "\udc00", "😀".repeat(81)}) {
            expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, name, values("model", "key")));
        }
        ApiProfilesStore.create(ACCOUNT, OWNER, "😀".repeat(80), values("model", "key"));
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "bad-key", values("model", "\ud800")));
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "mnn-incompatible", new AiSummarySettings.Config("http://127.0.0.1:8080/v1",
                "mnn-local", "", 4096, true, 32000, AiSummarySettings.ServiceType.MNN_LOCAL)));
        while (all().size() < ApiProfilesStore.MAX_PROFILES) ApiProfilesStore.create(ACCOUNT, OWNER, "profile-" + all().size(), values("model", ""));
        String firstId = all().get(0).profileId;
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "21st", values("model", "")));
        check(all().size() == 20 && all().get(0).profileId.equals(firstId), "capacity silently evicted a profile");
    }
    private static void storageFailures() {
        reset(); AiSummarySettings.Config first = selected();
        AiSummarySettings.Config other = ApiProfilesStore.create(ACCOUNT, OWNER, "other", values("model", "key"));
        String previous = disk();
        SummaryPrivateStorage.failWrites = 1;
        expect(() -> ApiProfilesStore.select(ACCOUNT, OWNER, other.profileId));
        check(disk().equals(previous) && selected().profileId.equals(first.profileId), "failed select switched service");
        SummaryPrivateStorage.failWrites = 1;
        expect(() -> ApiProfilesStore.delete(ACCOUNT, OWNER, first.profileId, first.profileRevision));
        check(selected().profileId.equals(first.profileId), "failed deletion cleared current profile");
        SummaryPrivateStorage.failReads = 1;
        expect(ApiProfilesStoreTest::selected);
        check(disk().equals(previous), "read failure rewrote configuration");
    }
    private static void corruption() throws Exception {
        reset(); selected();
        String plain = SummaryPrivateStorage.read(NAMESPACE, ACCOUNT, OWNER, ApiProfilesStore.MAX_STORAGE_BYTES);
        JSONObject broken = new JSONObject(plain).put("selected_id", "missing-profile");
        SummaryPrivateStorage.write(NAMESPACE, ACCOUNT, OWNER, broken.toString(), ApiProfilesStore.MAX_STORAGE_BYTES);
        String disk = disk();
        expect(ApiProfilesStoreTest::selected); expect(ApiProfilesStoreTest::all);
        expect(() -> ApiProfilesStore.create(ACCOUNT, OWNER, "new", values("new", "")));
        check(disk.equals(disk()), "unknown selected ID caused a fallback rewrite");
        broken = new JSONObject(plain);
        JSONArray profiles = broken.getJSONArray("profiles");
        profiles.put(new JSONObject(profiles.getJSONObject(0).toString()));
        SummaryPrivateStorage.write(NAMESPACE, ACCOUNT, OWNER, broken.toString(), ApiProfilesStore.MAX_STORAGE_BYTES);
        expect(ApiProfilesStoreTest::all);
        broken = new JSONObject(plain);
        broken.getJSONArray("profiles").getJSONObject(0).put("service_type", "AUTO");
        SummaryPrivateStorage.write(NAMESPACE, ACCOUNT, OWNER, broken.toString(), ApiProfilesStore.MAX_STORAGE_BYTES);
        expect(ApiProfilesStoreTest::all);
    }
    private static void legacy(String key, AiSummarySettings.ServiceType type, int tokens) {
        MessagesController.getMainSettings(ACCOUNT).edit().putString("local_ai_summary_owner_id", Long.toString(OWNER))
                .putString("local_ai_summary_base_url", "https://example.invalid/custom/v1").putString("local_ai_summary_model", "legacy-model")
                .putInt("local_ai_summary_max_output_tokens", tokens).putInt("local_ai_summary_stream", 1)
                .putInt("local_ai_summary_input_character_budget", 32000)
                .putInt("local_ai_summary_service_type", type == AiSummarySettings.ServiceType.MNN_LOCAL ? 1 : 0).commit();
        AiSummarySecretStore.save(ACCOUNT, OWNER, key);
    }
    private static List<AiSummarySettings.Config> all() { return ApiProfilesStore.list(ACCOUNT, OWNER); }
    private static AiSummarySettings.Config selected() { return AiSummarySettings.load(ACCOUNT, OWNER); }
    private static AiSummarySettings.Config values(String model, String key) {
        return new AiSummarySettings.Config("https://example.invalid/v1", model, key, 1024, true, 12000, AiSummarySettings.ServiceType.GENERIC);
    }
    private static String disk() { return SummaryPrivateStorage.FILES.get(SummaryPrivateStorage.key(NAMESPACE, ACCOUNT, OWNER)); }
    private static void reset() {
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        MessagesController.getMainSettings(ACCOUNT).values.clear();
        SummaryPrivateStorage.reset(); AiSummarySecretStore.reset();
    }
    private interface Checked { void run() throws Exception; }
    private static void run(String name, Checked body) throws Exception { body.run(); cases++; System.out.println("PASS " + name); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expect(Runnable action) { assertions++; try { action.run(); } catch (RuntimeException expected) { return; } throw new AssertionError("expected rejection"); }
}
