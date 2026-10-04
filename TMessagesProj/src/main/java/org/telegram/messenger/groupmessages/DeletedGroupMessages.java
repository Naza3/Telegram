/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Owner-bound asynchronous boundary. Retention failures never interrupt native message deletion. */
public final class DeletedGroupMessages {
    public interface Callback { void onResult(List<DeletedMessageRecord> records, boolean failed); }
    public interface ClearCallback { void onResult(boolean failed); }

    private static final DispatchQueue queue = new DispatchQueue("deletedGroupMessages");
    private static final DeletedMessageStore[] stores = new DeletedMessageStore[UserConfig.MAX_ACCOUNT_COUNT];

    private DeletedGroupMessages() { }

    public static boolean isEnabled(int account, long owner, long dialogId) {
        try {
            return owns(account, owner) && dialogId < 0 && Build.VERSION.SDK_INT >= 23
                    && GroupMessageSettings.get(account, dialogId).antiRevoke;
        } catch (Exception ignored) { return false; }
    }

    public static void retain(int account, long owner, List<DeletedMessageRecord> records) {
        if (records == null || records.isEmpty() || !owns(account, owner)) return;
        ArrayList<DeletedMessageRecord> snapshot = new ArrayList<>(records);
        queue.postRunnable(() -> {
            if (!owns(account, owner)) return;
            try {
                ArrayList<DeletedMessageRecord> enabled = new ArrayList<>();
                for (DeletedMessageRecord record : snapshot) {
                    if (record.ownerId == owner && isEnabled(account, owner, record.dialogId)) enabled.add(record);
                }
                if (!enabled.isEmpty() && owns(account, owner)) store(account).putAll(owner, enabled);
            } catch (Exception ignored) {
                FileLog.e("Could not retain deleted group message copies");
            }
        });
    }

    public static void getRecords(int account, long owner, long dialogId, Callback callback) {
        queue.postRunnable(() -> {
            List<DeletedMessageRecord> result = Collections.emptyList();
            boolean failed = false;
            try {
                requireOwner(account, owner);
                result = store(account).read(owner, dialogId);
                requireOwner(account, owner);
            } catch (Exception ignored) {
                failed = true;
                result = Collections.emptyList();
            }
            final List<DeletedMessageRecord> records = result;
            final boolean didFail = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) callback.onResult(owns(account, owner) ? records : Collections.emptyList(),
                        didFail || !owns(account, owner));
            });
        });
    }

    /** Call from the same native storage queue as message deletion to preserve capture/delete order. */
    public static void removeIds(int account, long owner, long dialogId, List<Integer> ids) {
        if (!owns(account, owner) || dialogId >= 0 || ids == null || ids.isEmpty()) return;
        ArrayList<Integer> snapshot = new ArrayList<>();
        for (Integer id : ids) if (id != null && id > 0) snapshot.add(id);
        if (snapshot.isEmpty()) return;
        mutate(account, owner, () -> store(account).removeIds(owner, dialogId, snapshot), null);
    }

    public static void removeSender(int account, long owner, long dialogId, long senderId) {
        if (dialogId >= 0 || senderId == 0) return;
        mutate(account, owner, () -> store(account).removeSender(owner, dialogId, senderId), null);
    }

    public static void removeTopic(int account, long owner, long dialogId, long topicId) {
        if (dialogId >= 0 || topicId <= 0) return;
        mutate(account, owner, () -> store(account).removeTopic(owner, dialogId, topicId), null);
    }

    public static void removeDateRange(int account, long owner, long dialogId, int minDate, int maxDate) {
        if (dialogId >= 0) return;
        mutate(account, owner, () -> store(account).removeDateRange(owner, dialogId, minDate, maxDate), null);
    }

    public static void clear(int account, long owner, long dialogId, ClearCallback callback) {
        mutate(account, owner, () -> {
            if (dialogId == 0) store(account).clearOwner(owner);
            else store(account).clearDialog(owner, dialogId);
        }, callback);
    }

    /** Owner captured before clearConfig; cleanup remains valid after this account slot is reused. */
    public static void logout(int account, long owner) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0) return;
        queue.postRunnable(() -> {
            try { store(account).clearOwner(owner); }
            catch (Exception ignored) { FileLog.e("Could not clear deleted group message copies on logout"); }
        });
    }

    private static void mutate(int account, long owner, Runnable action, ClearCallback callback) {
        queue.postRunnable(() -> {
            boolean failed = false;
            try {
                requireOwner(account, owner);
                action.run();
                requireOwner(account, owner);
            } catch (Exception ignored) {
                failed = true;
                FileLog.e("Could not clear deleted group message copies");
            }
            final boolean didFail = failed;
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onResult(didFail || !owns(account, owner)));
        });
    }

    private static boolean owns(int account, long owner) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && owner > 0
                && UserConfig.getInstance(account).getClientUserId() == owner;
    }

    private static void requireOwner(int account, long owner) {
        if (!owns(account, owner)) throw new IllegalStateException("Account owner changed");
    }

    private static DeletedMessageStore store(int account) {
        if (account < 0 || account >= stores.length) throw new IllegalArgumentException("Invalid account");
        if (stores[account] == null) {
            File root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "deleted_group_messages_" + account);
            stores[account] = new DeletedMessageStore(root, new DeletedMessageStore.KeyProvider() {
                @Override public SecretKey getKey(long owner, boolean create) throws Exception {
                    if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("Secure storage unavailable");
                    return Api23.key(account, owner, create);
                }
                @Override public void deleteKey(long owner) throws Exception {
                    if (Build.VERSION.SDK_INT >= 23) Api23.keys().deleteEntry(alias(account, owner));
                }
            });
        }
        return stores[account];
    }

    private static String alias(int account, long owner) {
        return "telegram.deleted_group_messages.v1." + account + "." + owner;
    }

    @TargetApi(23)
    private static final class Api23 {
        static KeyStore keys() throws Exception {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            return keys;
        }
        static SecretKey key(int account, long owner, boolean create) throws Exception {
            SecretKey key = (SecretKey) keys().getKey(alias(account, owner), null);
            if (key == null && create) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(alias(account, owner),
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                key = generator.generateKey();
            }
            if (key == null) throw new IllegalStateException("Missing deleted message storage key");
            return key;
        }
    }
}
