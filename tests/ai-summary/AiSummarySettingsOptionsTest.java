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
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512, true, 32001)) != null,
                "unbounded budget rejected");
        check(AiSummarySettings.validate(new AiSummarySettings.Config(old.baseUrl, old.model, "", 512, true, 32000)) == null,
                "supported profile accepted");
        System.out.println("AiSummarySettingsOptionsTest: 6 assertions passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
