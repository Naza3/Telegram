package org.telegram.messenger.ai;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

public final class AiSummarySettingsOptionsTest {
    public static void main(String[] args) {
        int account = 0;
        long owner = 1000;
        UserConfig.getInstance(account).setClientUserId(owner);
        MessagesController.getMainSettings(account).values.clear();
        AiSummarySettings.Config old = new AiSummarySettings.Config("http://127.0.0.1:8080/v1", "mnn-local", "", 512);
        check(!old.stream && old.inputCharacterBudget == 6000, "old configurations remain non-streaming");
        AiSummarySettings.Config configured = new AiSummarySettings.Config(old.baseUrl, old.model, "", 1024, true, 12000);
        AiSummarySettings.save(account, owner, configured);
        AiSummarySettings.Config loaded = AiSummarySettings.load(account, owner);
        check(loaded.stream && loaded.inputCharacterBudget == 12000 && loaded.maxOutputTokens == 1024,
                "stream and context profile survive a settings reload");
        UserConfig.getInstance(account).setClientUserId(2000);
        loaded = AiSummarySettings.load(account, 2000);
        check(!loaded.stream && loaded.inputCharacterBudget == 6000, "account slot cannot inherit previous runtime settings");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512, true, 2047)) != null,
                "too-small budget rejected before sending");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512, true, 64001)) != null,
                "unbounded budget rejected");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512, true, 32000)) == null,
                "supported profile accepted");
        AiSummarySettings.Config maximum = new AiSummarySettings.Config(old.baseUrl, old.model, "", 8192, true, 64000);
        check(AiSummarySettings.validate(maximum) == null && AiSummarySettings.MAX_INPUT_CHARACTER_BUDGET == 64000,
                "64k context accepted without reducing the existing 8192 output limit");
        AiSummarySettings.save(account, 2000, maximum);
        loaded = AiSummarySettings.load(account, 2000);
        check(loaded.inputCharacterBudget == 64000 && loaded.maxOutputTokens == 8192,
                "64k profile survives save and reload without normalization to the old bound");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 8193, true, 64000)) != null,
                "output upper bound remains unchanged");
        check(old.serviceType == AiSummarySettings.ServiceType.GENERIC,
                "loopback address alone does not imply an MNN server limit");
        AiSummarySettings.Config mnn = new AiSummarySettings.Config(old.baseUrl, old.model, "", 2048,
                true, 64000, AiSummarySettings.ServiceType.MNN_LOCAL);
        check(AiSummarySettings.validate(mnn) == null, "explicit supported MNN profile accepted");
        AiSummarySettings.save(account, 2000, mnn);
        check(AiSummarySettings.load(account, 2000).serviceType == AiSummarySettings.ServiceType.MNN_LOCAL,
                "explicit service profile survives reload");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 4096,
                true, 64000, AiSummarySettings.ServiceType.MNN_LOCAL)).contains("2048"),
                "MNN profile fails before an incompatible HTTP request");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 4096,
                true, 64000, AiSummarySettings.ServiceType.GENERIC)) == null,
                "generic OpenAI servers retain independent output limits");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512,
                false, 6000, null)) != null, "missing service type is invalid");
        System.out.println("AiSummarySettingsOptionsTest: 15 assertions passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
