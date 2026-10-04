/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.ApiProfilesStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FeatureUi;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/** Account-bound profiles; editing and testing never silently select another service. */
public final class ApiProfilesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final int account;
    private final long ownerId;
    private final Runnable onChanged;
    private final ArrayList<AiSummarySettings.Config> profiles = new ArrayList<>();
    private final ArrayList<View> editorFields = new ArrayList<>();
    private final ArrayList<Runnable> themeBindings = new ArrayList<>();
    private final ArrayList<RadioCell> serviceTypes = new ArrayList<>();
    private String selectedId = "";
    private LinearLayout content;
    private AiSummarySettings.Config opening;
    private EditTextBoldCursor name, address, model, key, output, budget;
    private AiSummarySettings.ServiceType selectedServiceType;
    private TextCheckCell stream;
    private TextView status;
    private TextSettingsCell cancelTest;
    private ActionBarMenuItem doneButton;
    private AiSummaryClient diagnostic;
    private boolean editing, busy, destroyed, invalidated;
    private int operation;

    public ApiProfilesActivity(int account, long ownerId, Runnable onChanged) {
        this.account = account;
        this.ownerId = ownerId;
        this.onChanged = onChanged;
        setCurrentAccount(account);
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("模型 API 配置");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) back();
                else if (id == 1 && editing && sameOwner() && !busy) save();
            }
        });
        doneButton = actionBar.createMenu().addItemWithWidth(1, R.drawable.ic_ab_done, dp(56),
                LocaleController.getString(R.string.Save));
        doneButton.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        load();
        return scroll;
    }

    @Override public void onPause() {
        stopDiagnostic("测试已停止；未保存设置。");
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        destroyed = true; operation++;
        stopDiagnostic(null);
        clearEditor(); profiles.clear();
        if (content != null) resetContent();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }

    @Override public boolean onBackPressed(boolean invoked) {
        if (editing || diagnostic != null || busy) { if (invoked) back(); return false; }
        return super.onBackPressed(invoked);
    }

    private void back() {
        if (diagnostic != null) { stopDiagnostic("测试已停止；未保存设置。"); return; }
        if (busy) return;
        if (editing) { clearEditor(); editing = false; load(); }
        else finishFragment();
    }

    private boolean sameOwner() {
        return !destroyed && !invalidated && ownerId > 0 && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean active(int generation) { return generation == operation && sameOwner() && !isFinished; }

    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (sameOwner()) return;
        invalidated = true; operation++; stopDiagnostic(null); busy = false;
        clearEditor(); profiles.clear();
        if (content != null) {
            resetContent();
            doneButton.setVisibility(View.GONE);
            text("账号已切换或退出，请从当前账号重新打开 API 配置。", false);
        }
    }

    private void load() {
        if (!sameOwner() || content == null) return;
        final int generation = ++operation;
        busy = true; editing = false; clearEditor(); profiles.clear();
        resetContent(); doneButton.setVisibility(View.GONE); text("正在读取模型配置…", false);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                List<AiSummarySettings.Config> values = ApiProfilesStore.list(account, ownerId);
                final String selectedResult = ApiProfilesStore.selectedId(account, ownerId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; selectedId = selectedResult; profiles.addAll(values); showList(null);
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; showList("无法读取 API 配置，请重试。已有配置不会被覆盖。");
                });
            }
        });
    }

    private void showList(String error) {
        resetContent();
        doneButton.setVisibility(View.GONE);
        actionBar.setTitle("模型 API 配置");
        text("每项配置独立保存地址、模型与密钥。切换只影响下一次任务，正在运行的总结继续使用启动时的配置。", false);
        if (error != null) { text(error, false); action("重新读取", this::load); return; }
        if (selectedId.isEmpty()) text("尚未选择 API 配置。请明确选择一项后开始总结，不会自动切换到其他服务。", false);
        action("新增 API 配置", () -> showEditor(null));
        spacer();
        for (AiSummarySettings.Config config : profiles) {
            TextDetailSettingsCell details = new TextDetailSettingsCell(content.getContext());
            details.setMultilineDetail(true);
            details.setTextAndValue(config.profileName + (config.profileId.equals(selectedId) ? " · 当前使用" : ""),
                    (config.serviceType == AiSummarySettings.ServiceType.MNN_LOCAL ? "MNN 本机" : "OpenAI 兼容")
                            + " · " + (config.model.isEmpty() ? "服务当前模型" : config.model) + "\n" + config.baseUrl, true);
            bindTheme(() -> {
                details.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                details.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                details.getValueTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            });
            content.addView(details, LayoutHelper.createLinear(-1, -2));
            action("使用此配置", () -> select(config));
            action("编辑配置", () -> showEditor(config));
            action("测试连接", () -> test(config));
            action("删除配置", () -> confirmDelete(config), Theme.key_text_RedRegular, false);
            spacer();
        }
        status = text(profiles.size() + " / 20 项 · 仅当前账号本机保存", false);
        cancelTest = action("取消连接测试", () -> stopDiagnostic("测试已停止。"));
        cancelTest.setVisibility(View.GONE);
        // Cancellation remains available while all mutating actions are blocked.
        cancelTest.setOnClickListener(view -> stopDiagnostic("测试已停止。"));
    }

    private void showEditor(AiSummarySettings.Config profile) {
        if (!sameOwner() || busy) return;
        clearEditor(); editing = true;
        opening = profile == null ? new AiSummarySettings.Config(AiSummarySettings.DEFAULT_BASE_URL,
                AiSummarySettings.DEFAULT_MODEL, "") : profile;
        resetContent();
        actionBar.setTitle(profile == null ? "新增 API 配置" : "编辑 API 配置");
        doneButton.setVisibility(View.VISIBLE);
        text("基本信息", true);
        name = edit("配置名称（最多 80 字符）", profile == null ? "" : profile.profileName, InputType.TYPE_CLASS_TEXT);
        spacer();
        text("连接设置", true);
        selectedServiceType = opening.serviceType;
        for (AiSummarySettings.ServiceType type : AiSummarySettings.ServiceType.values()) {
            RadioCell choice = new RadioCell(content.getContext(), getResourceProvider());
            choice.setText(type == AiSummarySettings.ServiceType.MNN_LOCAL ? "MNN Chat 本机服务" : "通用 OpenAI 兼容服务",
                    type == opening.serviceType, true);
            choice.setTag(type); serviceTypes.add(choice); editorFields.add(choice);
            bindTheme(() -> {
                choice.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                choice.setBackground(Theme.createSelectorWithBackgroundDrawable(
                        getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
            });
            choice.setOnClickListener(view -> {
                if (!sameOwner() || busy) return;
                selectedServiceType = type;
                for (RadioCell cell : serviceTypes) cell.setChecked(cell == choice, true);
            });
            content.addView(choice, LayoutHelper.createLinear(-1, -2));
        }
        fieldLabel("服务地址");
        address = edit("http://127.0.0.1:8080/v1", opening.baseUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fieldLabel("模型名称（可留空）");
        model = edit("留空使用服务当前模型", opening.model, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text("MNN 需先加载模型并开启 API 服务；填写模型名不会切换 MNN 已加载的模型。", false);
        fieldLabel("API Key");
        key = edit("服务未开启鉴权时可留空", opening.apiKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance()); key.setSaveEnabled(false);
        text("密钥随本项配置独立加密保存。新建配置不会复制其他配置的密钥。", false);
        text("生成设置", true);
        fieldLabel("最大输出 tokens");
        output = edit("512", Integer.toString(opening.maxOutputTokens), InputType.TYPE_CLASS_NUMBER);
        text("可填写 64–8192；MNN 本机服务最高 2048。", false);
        fieldLabel("上下文字符预算");
        budget = edit("6000", Integer.toString(opening.inputCharacterBudget), InputType.TYPE_CLASS_NUMBER);
        text("可填写 2048–" + AiSummarySettings.MAX_INPUT_CHARACTER_BUDGET
                + "。字符预算不是模型 token 数；还需为核心要求、输入说明与输出预留空间。", false);
        stream = new TextCheckCell(content.getContext(), getResourceProvider());
        stream.setTextAndCheck("流式显示", opening.stream, false);
        final TextCheckCell streamCell = stream;
        bindTheme(() -> {
            streamCell.setBackground(Theme.createSelectorWithBackgroundDrawable(
                    getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
            streamCell.setColors(Theme.key_windowBackgroundWhiteBlackText, Theme.key_switchTrack,
                    Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        });
        stream.setOnClickListener(view -> { if (sameOwner() && !busy) streamCell.setChecked(!streamCell.isChecked()); });
        editorFields.add(stream);
        content.addView(stream, LayoutHelper.createLinear(-1, -2));
        text("服务支持时，生成的内容会逐步显示。", false);
        action("测试连接", () -> { AiSummarySettings.Config config = snapshot(); if (config != null) test(config); });
        cancelTest = action("取消连接测试", () -> stopDiagnostic("测试已停止；未保存设置。"));
        cancelTest.setVisibility(View.GONE); cancelTest.setOnClickListener(view -> stopDiagnostic("测试已停止；未保存设置。"));
        status = text("连接测试只发送固定短消息，不读取群消息，也不会自动保存。填写完成后，点击右上角保存。", false);
    }

    private AiSummarySettings.Config snapshot() {
        int tokens, characters;
        try { tokens = Integer.parseInt(output.getText().toString().trim()); characters = Integer.parseInt(budget.getText().toString().trim()); }
        catch (NumberFormatException ignored) { showStatus("输出上限与字符预算需要填写有效整数。"); return null; }
        AiSummarySettings.Config config = opening.withValues(address.getText().toString(), model.getText().toString(),
                key.getText().toString(), tokens, stream.isChecked(), characters, selectedServiceType);
        String error = AiSummarySettings.validate(config);
        if (error != null) { showStatus(error); return null; }
        return config;
    }

    private void save() {
        AiSummarySettings.Config values = snapshot();
        if (values == null) return;
        String profileName = name.getText().toString().trim();
        if (profileName.isEmpty() || profileName.codePointCount(0, profileName.length()) > 80) {
            name.setError("请填写 1–80 个字符的名称"); name.requestFocus(); return;
        }
        final AiSummarySettings.Config expected = opening;
        mutate(() -> {
            if (expected.profileId.isEmpty()) ApiProfilesStore.create(account, ownerId, profileName, values);
            else ApiProfilesStore.update(account, ownerId, expected.profileId, expected.profileRevision, profileName, values);
        }, false, "保存失败。配置可能已被修改或删除，请保留当前填写内容并重新打开该配置。");
    }

    private void select(AiSummarySettings.Config config) {
        mutate(() -> ApiProfilesStore.select(account, ownerId, config.profileId), true,
                "选择失败，该配置可能已删除，请刷新列表。");
    }

    private void confirmDelete(AiSummarySettings.Config config) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("删除 API 配置")
                .setMessage("删除「" + config.profileName + "」及其密钥？"
                        + (config.profileId.equals(selectedId) ? "删除后需明确选择另一项，不会自动回退。" : ""))
                .setPositiveButton("删除", (ignored, which) -> mutate(() -> ApiProfilesStore.delete(account, ownerId,
                        config.profileId, config.profileRevision), false, "删除失败，配置可能已变化，请刷新后重试。"))
                .setNegativeButton("取消", null).create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private interface Mutation { void run(); }
    private void mutate(Mutation mutation, boolean finishAfter, String error) {
        if (!sameOwner() || busy) return;
        final int generation = ++operation; busy = true; setFieldsEnabled(false); status.setText("正在保存配置…");
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (!sameOwner()) return;
                mutation.run();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; setFieldsEnabled(true);
                    if (onChanged != null) onChanged.run();
                    if (finishAfter) finishFragment(); else { clearEditor(); editing = false; load(); }
                });
            } catch (RuntimeException failure) {
                // Store parameter validation uses fixed safe messages; arbitrary storage errors stay generic.
                final String notice = failure instanceof IllegalArgumentException && failure.getMessage() != null
                        ? failure.getMessage() : error;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; setFieldsEnabled(true); showStatus(notice);
                });
            }
        });
    }

    private void test(AiSummarySettings.Config config) {
        if (!sameOwner() || busy) return;
        final int generation = ++operation;
        busy = true; setFieldsEnabled(false); cancelTest.setVisibility(View.VISIBLE);
        status.setText("正在测试连接…测试不会保存配置。");
        diagnostic = new AiSummaryClient();
        diagnostic.testConnection(config, new AiSummaryClient.DiagnosticCallback() {
            @Override public void onSuccess(AiSummaryClient.DiagnosticResult result) {
                if (!active(generation)) return;
                diagnostic = null; busy = false; setFieldsEnabled(true); cancelTest.setVisibility(View.GONE);
                status.setText("连接成功 · " + result.elapsedMs + " 毫秒\n" + (result.reportedModel == null
                        ? "服务未报告实际模型名称。" : "服务报告模型：" + result.reportedModel)
                        + "\n仅验证普通请求；未保存或切换配置。");
            }
            @Override public void onError(String message) { onError(message, null); }
            @Override public void onError(String message, AiSummaryClient.DiagnosticErrorInfo info) {
                if (!active(generation)) return;
                diagnostic = null; busy = false; setFieldsEnabled(true); cancelTest.setVisibility(View.GONE);
                String detail = info == null ? "" : "\nHTTP " + info.status + " · " + info.responseFormat
                        + (info.serverMessage.isEmpty() ? "" : "\n" + info.serverMessage);
                status.setText(message + detail + "\n可修改设置后重新测试；未保存或切换配置。");
                status.setTextIsSelectable(true);
                status.setLinksClickable(false);
            }
        });
    }

    private void stopDiagnostic(String notice) {
        if (diagnostic == null) return;
        operation++; diagnostic.cancel(); diagnostic = null; busy = false; setFieldsEnabled(true);
        if (cancelTest != null) cancelTest.setVisibility(View.GONE);
        if (notice != null && status != null && !destroyed) status.setText(notice);
    }

    private void clearEditor() {
        if (key != null) key.setText("");
        opening = null; name = address = model = key = output = budget = null;
        serviceTypes.clear(); selectedServiceType = null; stream = null; editorFields.clear();
    }

    private void showStatus(String notice) {
        TextView view = status;
        if (view == null) return;
        view.setText(notice);
        // Saving is available in the action bar, so validation feedback may be below the viewport.
        view.post(() -> {
            if (view.isAttachedToWindow()) view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
        });
    }

    private void setFieldsEnabled(boolean enabled) {
        for (View view : editorFields) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.5f);
        }
        if (doneButton != null) { doneButton.setEnabled(enabled); doneButton.setAlpha(enabled ? 1f : 0.5f); }
    }

    private void resetContent() {
        content.removeAllViews();
        themeBindings.clear();
    }

    private void bindTheme(Runnable update) {
        themeBindings.add(update);
        update.run();
    }

    private void spacer() {
        content.addView(new View(content.getContext()), LayoutHelper.createLinear(-1, 12));
    }

    private TextView text(String value, boolean bold) {
        if (bold) return header(value, Theme.key_windowBackgroundWhiteBlueHeader);
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(content.getContext(), 24, getResourceProvider());
        cell.setText(value);
        bindTheme(() -> {
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            cell.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
            cell.getTextView().setLinkTextColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
        });
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        return cell.getTextView();
    }

    private void fieldLabel(String value) {
        header(value, Theme.key_windowBackgroundWhiteBlackText);
    }

    private TextView header(String value, int colorKey) {
        HeaderCell cell = new HeaderCell(content.getContext(), 21, getResourceProvider());
        cell.setText(value);
        bindTheme(() -> {
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            cell.getTextView().setTextColor(getThemedColor(colorKey));
        });
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        return cell.getTextView();
    }

    private TextSettingsCell action(String label, Runnable run) {
        return action(label, run, Theme.key_windowBackgroundWhiteBlueText, true);
    }

    private TextSettingsCell action(String label, Runnable run, int colorKey, boolean divider) {
        TextSettingsCell cell = new TextSettingsCell(content.getContext(), 21, getResourceProvider());
        cell.setText(label, divider);
        cell.setFocusable(true);
        bindTheme(() -> {
            cell.setTextColor(getThemedColor(colorKey));
            cell.setBackground(Theme.createSelectorWithBackgroundDrawable(
                    getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        });
        cell.setOnClickListener(ignored -> { if (sameOwner() && !busy) run.run(); });
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        return cell;
    }

    private EditTextBoldCursor edit(String hint, String value, int inputType) {
        EditTextBoldCursor view = new EditTextBoldCursor(content.getContext()); view.setInputType(inputType);
        view.setSingleLine(true); view.setHint(hint); view.setText(value); view.setSaveEnabled(false);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        FrameLayout field = new FrameLayout(content.getContext());
        field.addView(view, LayoutHelper.createFrame(-1, -2, Gravity.TOP, 21, 0, 21, 8));
        bindTheme(() -> {
            field.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            FeatureUi.styleInput(view, getResourceProvider(), false);
            view.setMinHeight(dp(52));
        });
        content.addView(field, LayoutHelper.createLinear(-1, -2));
        editorFields.add(view);
        return view;
    }

    private void refreshTheme() {
        if (fragmentView != null) fragmentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        for (Runnable update : themeBindings) update.run();
        if (content != null) content.invalidate();
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        ThemeDescription.ThemeDescriptionDelegate delegate = this::refreshTheme;
        for (int color : new int[] { Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText2,
                Theme.key_windowBackgroundWhiteGrayText4, Theme.key_windowBackgroundWhiteBlueHeader,
                Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteLinkText,
                Theme.key_windowBackgroundWhiteHintText, Theme.key_windowBackgroundWhiteInputField,
                Theme.key_windowBackgroundWhiteInputFieldActivated, Theme.key_text_RedRegular,
                Theme.key_listSelector, Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_divider }) {
            descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, color));
        }
        descriptions.add(new ThemeDescription(content, ThemeDescription.FLAG_CHECKBOX, new Class[] { RadioCell.class },
                new String[] { "radioButton" }, null, null, null, Theme.key_radioBackground));
        descriptions.add(new ThemeDescription(content, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[] { RadioCell.class },
                new String[] { "radioButton" }, null, null, null, Theme.key_radioBackgroundChecked));
        return descriptions;
    }

    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
