/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

/** Process-only fingerprint gate. No saved state or lifecycle event can grant access. */
public final class NotesGateState {
    private long nextToken;
    private long host;
    private long attempt;
    private boolean resumed;
    private boolean screenOn;
    private boolean unlocked;

    public synchronized long attachHost() {
        host = token();
        attempt = 0;
        resumed = false;
        return host;
    }

    public synchronized void onResume(long host, boolean screenOn) {
        if (!owns(host)) return;
        this.resumed = true;
        this.screenOn = screenOn;
        if (!screenOn) lock();
    }

    /** Invalidates scans, but preserves a successful Notes-to-Telegram handoff. */
    public synchronized void onPause(long host) {
        if (!owns(host)) return;
        resumed = false;
        attempt = 0;
    }

    public synchronized void detach(long host) {
        if (!owns(host)) return;
        onPause(host);
        this.host = 0;
    }

    public synchronized void onScreenOff() {
        screenOn = false;
        lock();
    }

    /** The facade must call this on real background transitions, before cancelling the driver. */
    public synchronized void lock() {
        unlocked = false;
        attempt = 0;
    }

    /** Zero means authentication must not start; duplicate starts never share an attempt. */
    public synchronized long beginAuthentication(long host) {
        if (!owns(host) || !resumed || !screenOn || unlocked || attempt != 0) return 0;
        return attempt = token();
    }

    public synchronized boolean tryUnlock(long host, long attempt) {
        if (!owns(host) || attempt == 0 || this.attempt != attempt || !resumed
                || !screenOn || unlocked) return false;
        this.attempt = 0;
        unlocked = true;
        return true;
    }

    public synchronized void authenticationEnded(long host, long attempt) {
        if (owns(host) && attempt != 0 && this.attempt == attempt) this.attempt = 0;
    }

    public synchronized boolean isUnlocked() { return unlocked; }
    public synchronized boolean isAuthenticating() { return attempt != 0; }

    private boolean owns(long candidate) { return candidate != 0 && candidate == host; }

    private long token() {
        // Refuse an impossible lifetime overflow instead of ever reusing a stale token.
        if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("指纹会话已失效，请重新启动应用。");
        return ++nextToken;
    }
}
