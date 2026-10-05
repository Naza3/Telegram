/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotesFingerprintAuthenticator;
import org.telegram.messenger.NotesGate;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.NotesCoverView;

/** A usable local notebook. Only its concealed gesture starts fingerprint authentication. */
public final class ShiyeNotesActivity extends Activity {
    private NotesCoverView notesView;
    private NotesFingerprintAuthenticator fingerprint;
    private AlertDialog authenticationDialog;
    private long host;
    private long attempt;
    private boolean resumed;
    private boolean openingProtectedActivity;

    @Override protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTheme(R.style.Theme_TMessages);
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xfff4f5f0));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ApplicationLoader.postInitApplication();
        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
        AndroidUtilities.fillStatusBarHeight(this, false);
        Theme.createCommonChatResources();
        NotesGate.lock();
        host = NotesGate.state().attachHost();
        NotesGate.attachNotes(this);
        fingerprint = new NotesFingerprintAuthenticator(this);
        notesView = new NotesCoverView(this, this::requestFingerprint);
        notesView.setFitsSystemWindows(true);
        setContentView(notesView);
        updateSystemBars();
    }

    private void updateSystemBars() {
        int color = Theme.getColor(Theme.key_windowBackgroundWhite);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        AndroidUtilities.setLightStatusBar(this, !Theme.isCurrentThemeDark());
        AndroidUtilities.setLightNavigationBar(this, !Theme.isCurrentThemeDark());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openingProtectedActivity = false;
        NotesGate.lock();
    }

    @Override protected void onResume() {
        super.onResume();
        openingProtectedActivity = false;
        resumed = true;
        NotesGate.state().onResume(host, NotesGate.isScreenInteractive(this));
        notesView.refreshTheme();
        updateSystemBars();
    }

    @Override protected void onPause() {
        resumed = false;
        NotesGate.state().onPause(host);
        if (!openingProtectedActivity) NotesGate.lock();
        cancelAuthentication();
        notesView.onHostPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        NotesGate.state().detach(host);
        cancelAuthentication();
        NotesGate.detachNotes(this);
        if (notesView != null) notesView.dispose();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AndroidUtilities.checkDisplaySize(this, configuration);
        notesView.refreshTheme();
        updateSystemBars();
    }

    @Override public void onBackPressed() {
        if (authenticationDialog != null) { cancelAuthentication(); return; }
        if (notesView.onBackPressed()) return;
        NotesGate.abandonPendingEntry();
        if (getCallingActivity() != null) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            moveTaskToBack(true);
        }
    }

    private void requestFingerprint() {
        if (!resumed || isFinishing() || authenticationDialog != null) return;
        NotesGate.state().onResume(host, NotesGate.isScreenInteractive(this));
        NotesFingerprintAuthenticator.Availability availability = fingerprint.availability();
        if (availability != NotesFingerprintAuthenticator.Availability.AVAILABLE) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("指纹验证")
                    .setMessage(availability == NotesFingerprintAuthenticator.Availability.NOT_ENROLLED
                            ? "请先在系统设置中录入指纹，然后返回并重新验证。"
                            : "当前设备的指纹验证不可用，仍可继续使用本地笔记。")
                    .setNegativeButton("关闭", null);
            if (availability == NotesFingerprintAuthenticator.Availability.NOT_ENROLLED) {
                builder.setPositiveButton("系统设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                    catch (RuntimeException ignored) { }
                });
            }
            builder.show();
            return;
        }
        final long authenticationAttempt = NotesGate.state().beginAuthentication(host);
        if (authenticationAttempt == 0) return;
        attempt = authenticationAttempt;
        notesView.setAuthenticationPending(true);
        TextView message = new TextView(this);
        message.setText("请使用指纹验证");
        message.setTextSize(16);
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        message.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        authenticationDialog = new AlertDialog.Builder(this)
                .setTitle("指纹验证")
                .setView(message)
                .setNegativeButton("取消", (dialog, which) -> cancelAuthentication())
                .create();
        authenticationDialog.setOnDismissListener(dialog -> cancelAuthentication());
        authenticationDialog.show();
        fingerprint.authenticate(new NotesFingerprintAuthenticator.Callback() {
            @Override public void onSuccess() {
                if (!resumed || !NotesGate.isScreenInteractive(ShiyeNotesActivity.this)
                        || !NotesGate.state().tryUnlock(host, authenticationAttempt)) return;
                openingProtectedActivity = true;
                cancelAuthentication();
                if (NotesGate.enterProtectedActivity(ShiyeNotesActivity.this)) {
                    finish();
                    overridePendingTransition(0, 0);
                } else {
                    openingProtectedActivity = false;
                    showAuthenticationError("暂时无法打开，请重试。");
                }
            }
            @Override public void onError(String error) {
                if (attempt != authenticationAttempt || !resumed) return;
                cancelAuthentication();
                showAuthenticationError(error);
            }
            @Override public void onHelp(String help) {
                if (attempt == authenticationAttempt && authenticationDialog != null && resumed) message.setText(help);
            }
        });
    }

    private void showAuthenticationError(String message) {
        if (!resumed || isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("指纹验证").setMessage(message)
                .setPositiveButton("关闭", null).show();
    }

    private void cancelAuthentication() {
        long previousAttempt = attempt;
        attempt = 0;
        NotesGate.state().authenticationEnded(host, previousAttempt);
        if (fingerprint != null) fingerprint.cancel();
        AlertDialog dialog = authenticationDialog;
        authenticationDialog = null;
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
        if (notesView != null) notesView.setAuthenticationPending(false);
    }

    public void onGateLocked() { cancelAuthentication(); }
}
