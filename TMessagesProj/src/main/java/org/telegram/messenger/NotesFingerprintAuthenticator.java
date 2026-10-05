/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Strict fingerprint-only authentication; independent of Telegram's passcode and biometric UI. */
public final class NotesFingerprintAuthenticator {
    public enum Availability { AVAILABLE, UNSUPPORTED, NO_HARDWARE, NOT_ENROLLED, UNAVAILABLE }

    public interface Callback {
        void onSuccess();
        void onError(String message);
        void onHelp(String message);
    }

    interface Driver {
        Availability availability();
        void authenticate(Callback callback);
        void cancel();
    }

    interface Dispatcher { void post(Runnable runnable); }

    private final Driver driver;
    private final Dispatcher dispatcher;
    private long generation;
    private boolean active;

    public NotesFingerprintAuthenticator(Context context) {
        this(Build.VERSION.SDK_INT >= 23 ? new Api23(context.getApplicationContext()) : new Unsupported(),
                runnable -> new Handler(Looper.getMainLooper()).post(runnable));
    }

    NotesFingerprintAuthenticator(Driver driver, Dispatcher dispatcher) {
        this.driver = driver;
        this.dispatcher = dispatcher;
    }

    public Availability availability() {
        try {
            Availability value = driver.availability();
            return value == null ? Availability.UNAVAILABLE : value;
        } catch (RuntimeException error) {
            return Availability.UNAVAILABLE;
        }
    }

    public synchronized void authenticate(Callback callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        cancel();
        final long current = ++generation;
        active = true;
        Availability available = availability();
        if (available != Availability.AVAILABLE) {
            deliver(current, callback, true, message(available), false);
            return;
        }
        try {
            driver.authenticate(new Callback() {
                @Override public void onSuccess() { deliver(current, callback, true, null, true); }
                @Override public void onError(String message) { deliver(current, callback, true, message, false); }
                @Override public void onHelp(String message) { deliver(current, callback, false, message, false); }
            });
        } catch (RuntimeException error) {
            deliver(current, callback, true, "无法启动指纹验证，请重试。", false);
        }
    }

    /** Invalidate before cancellation: even a synchronous/late driver callback cannot unlock. */
    public synchronized void cancel() {
        generation++;
        active = false;
        try { driver.cancel(); } catch (RuntimeException ignored) { }
    }

    private void deliver(long current, Callback callback, boolean terminal, String message, boolean success) {
        dispatcher.post(() -> {
            synchronized (NotesFingerprintAuthenticator.this) {
                if (!active || generation != current) return;
                if (terminal) active = false;
                if (success) callback.onSuccess();
                else if (terminal) callback.onError(message == null || message.length() == 0
                        ? "指纹验证不可用，请重试。" : message);
                else callback.onHelp(message == null ? "请重试指纹验证。" : message);
            }
        });
    }

    private static String message(Availability available) {
        switch (available) {
            case UNSUPPORTED: return "此 Android 版本不支持指纹验证。";
            case NO_HARDWARE: return "未检测到可用的指纹传感器。";
            case NOT_ENROLLED: return "请先在系统安全设置中录入指纹。";
            default: return "指纹暂时不可用，请重试。";
        }
    }

    private static final class Unsupported implements Driver {
        @Override public Availability availability() { return Availability.UNSUPPORTED; }
        @Override public void authenticate(Callback callback) { callback.onError(message(Availability.UNSUPPORTED)); }
        @Override public void cancel() { }
    }

    @TargetApi(23)
    private static final class Api23 implements Driver {
        private static final String KEY_ALIAS = "shiye_notes_fingerprint_v1";
        // KeyStore work must not block the UI. The bounded queue cannot accumulate abandoned scans.
        private static final ThreadPoolExecutor KEY_WORKER = new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "notes-fingerprint-key");
                    thread.setDaemon(true);
                    return thread;
                });
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private long generation;
        private CancellationSignal signal;
        private Future<?> preparation;

        Api23(Context context) { this.context = context; }

        private FingerprintManager manager() {
            return (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        }

        @Override public Availability availability() {
            FingerprintManager manager = manager();
            if (manager == null || !manager.isHardwareDetected()) return Availability.NO_HARDWARE;
            return manager.hasEnrolledFingerprints() ? Availability.AVAILABLE : Availability.NOT_ENROLLED;
        }

        @Override public synchronized void authenticate(Callback callback) {
            cancel();
            final long current = ++generation;
            final CancellationSignal currentSignal = signal = new CancellationSignal();
            preparation = KEY_WORKER.submit(() -> {
                try {
                    Cipher cipher = newCipher();
                    byte[] challenge = new byte[32];
                    new SecureRandom().nextBytes(challenge);
                    main.post(() -> start(current, currentSignal, cipher, challenge, callback));
                } catch (Exception error) {
                    main.post(() -> fail(current, callback, "无法准备安全指纹验证，请重试。"));
                }
            });
        }

        private synchronized void start(long current, CancellationSignal currentSignal, Cipher cipher,
                byte[] challenge, Callback callback) {
            if (!isCurrent(current) || currentSignal.isCanceled()) return;
            try {
                FingerprintManager manager = manager();
                if (manager == null) throw new IllegalStateException();
                manager.authenticate(new FingerprintManager.CryptoObject(cipher), currentSignal, 0,
                        new FingerprintManager.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                                synchronized (Api23.this) {
                                    if (!isCurrent(current)) return;
                                    try {
                                        if (result == null || result.getCryptoObject() == null
                                                || result.getCryptoObject().getCipher() != cipher
                                                || cipher.doFinal(challenge).length < 16) {
                                            throw new IllegalStateException();
                                        }
                                        finish();
                                        callback.onSuccess();
                                    } catch (Exception error) {
                                        fail(current, callback, "安全指纹验证失败，请重试。" );
                                    }
                                }
                            }
                            @Override public void onAuthenticationError(int code, CharSequence ignored) {
                                String text = code == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT
                                        || code == 9 ? "指纹尝试次数过多，请稍后重试。"
                                        : code == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                                        ? "指纹验证已取消。" : "指纹暂时不可用，请重试。";
                                fail(current, callback, text);
                            }
                            @Override public void onAuthenticationHelp(int code, CharSequence ignored) {
                                help(current, callback, "请完整触碰指纹传感器后重试。");
                            }
                            @Override public void onAuthenticationFailed() {
                                help(current, callback, "指纹不匹配，请重试。");
                            }
                        }, main);
            } catch (RuntimeException error) {
                fail(current, callback, "无法启动指纹验证，请重试。");
            }
        }

        private boolean isCurrent(long current) { return generation == current && signal != null; }

        private synchronized void help(long current, Callback callback, String message) {
            if (isCurrent(current)) callback.onHelp(message);
        }

        private synchronized void fail(long current, Callback callback, String message) {
            if (!isCurrent(current)) return;
            finish();
            callback.onError(message);
        }

        private void finish() { signal = null; preparation = null; generation++; }

        @Override public synchronized void cancel() {
            generation++;
            CancellationSignal oldSignal = signal;
            signal = null;
            if (preparation != null) {
                preparation.cancel(false);
                if (preparation instanceof Runnable) KEY_WORKER.remove((Runnable) preparation);
                preparation = null;
            }
            if (oldSignal != null) oldSignal.cancel();
        }

        private static Cipher newCipher() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
            if (key == null) key = generateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key);
            } catch (KeyPermanentlyInvalidatedException invalidated) {
                // The key protects no stored data. A new enrollment still needs a fresh fingerprint.
                store.deleteEntry(KEY_ALIAS);
                cipher.init(Cipher.ENCRYPT_MODE, generateKey());
            }
            return cipher;
        }

        private static SecretKey generateKey() throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1);
            if (Build.VERSION.SDK_INT >= 24) spec.setInvalidatedByBiometricEnrollment(true);
            generator.init(spec.build());
            return generator.generateKey();
        }
    }
}
