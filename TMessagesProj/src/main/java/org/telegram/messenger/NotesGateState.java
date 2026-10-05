/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

/** Process-only fingerprint session. Lifecycle events can resume only an unexpired grant. */
public final class NotesGateState {
    public static final long MAX_GRACE_MS = 86_400_000L;
    private enum Session { LOCKED, FOREGROUND, BACKGROUND }

    private long nextToken;
    private long host;
    private long attempt;
    private boolean resumed;
    private boolean screenOn;
    private Session session = Session.LOCKED;
    private long deadline = -1;
    private long lastElapsed = -1;

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

    /** Explicit lock and screen-off revoke the session, including any background grace. */
    public synchronized void lock() {
        session = Session.LOCKED;
        attempt = 0;
        deadline = -1;
        lastElapsed = -1;
    }

    /**
     * Hide content immediately. Only the first foreground-to-background transition starts grace.
     * Both arguments are milliseconds; now must be a fresh elapsedRealtime reading, never wall time.
     */
    public synchronized void onBackground(long nowElapsedMs, long delayMs) {
        attempt = 0;
        if (!acceptTime(nowElapsedMs) || delayMs < 0 || delayMs > MAX_GRACE_MS) {
            lock();
            return;
        }
        if (session == Session.BACKGROUND) {
            expire(nowElapsedMs);
            return;
        }
        if (session != Session.FOREGROUND) return;
        if (delayMs == 0 || nowElapsedMs > Long.MAX_VALUE - delayMs) {
            lock();
            return;
        }
        deadline = nowElapsedMs + delayMs;
        session = Session.BACKGROUND;
    }

    /** The facade also checks device keyguard before supplying screenOn=true. */
    public synchronized boolean resumeSession(long nowElapsedMs, boolean screenOn) {
        this.screenOn = screenOn;
        if (!screenOn) {
            lock();
            return false;
        }
        if (!hasValidSession(nowElapsedMs)) return false;
        session = Session.FOREGROUND;
        deadline = -1;
        return true;
    }

    /** Checks/invalidates expiry, without granting content visibility or extending grace. */
    public synchronized boolean hasValidSession(long nowElapsedMs) {
        if (!acceptTime(nowElapsedMs)) return false;
        expire(nowElapsedMs);
        return session != Session.LOCKED;
    }

    /** Zero for locked or foreground sessions; callers must use hasValidSession to test validity. */
    public synchronized long remainingGraceMs(long nowElapsedMs) {
        if (!hasValidSession(nowElapsedMs) || session != Session.BACKGROUND) return 0;
        return deadline - nowElapsedMs;
    }

    /** Zero means authentication must not start; duplicate starts never share an attempt. */
    public synchronized long beginAuthentication(long host) {
        if (!owns(host) || !resumed || !screenOn || session != Session.LOCKED || attempt != 0) return 0;
        return attempt = token();
    }

    public synchronized boolean tryUnlock(long host, long attempt) {
        if (!owns(host) || attempt == 0 || this.attempt != attempt || !resumed
                || !screenOn || session != Session.LOCKED) return false;
        this.attempt = 0;
        session = Session.FOREGROUND;
        deadline = -1;
        lastElapsed = -1;
        return true;
    }

    public synchronized void authenticationEnded(long host, long attempt) {
        if (owns(host) && attempt != 0 && this.attempt == attempt) this.attempt = 0;
    }

    /** Background grants are deliberately invisible to async UI code using this accessor. */
    public synchronized boolean isUnlocked() { return session == Session.FOREGROUND; }
    public synchronized boolean isAuthenticating() { return attempt != 0; }

    private boolean owns(long candidate) { return candidate != 0 && candidate == host; }

    private boolean acceptTime(long nowElapsedMs) {
        if (nowElapsedMs < 0 || lastElapsed >= 0 && nowElapsedMs < lastElapsed) {
            lock();
            return false;
        }
        lastElapsed = nowElapsedMs;
        return true;
    }

    private void expire(long nowElapsedMs) {
        if (session == Session.BACKGROUND && nowElapsedMs >= deadline) lock();
    }

    private long token() {
        // Refuse an impossible lifetime overflow instead of ever reusing a stale token.
        if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("指纹会话已失效，请重新启动应用。");
        return ++nextToken;
    }
}
