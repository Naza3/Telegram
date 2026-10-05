/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

public final class NotesGateGraceTest {
    private static int checks;

    public static void main(String[] args) {
        graceBoundary();
        duplicateLifecycleEvents();
        noGrantWithoutFingerprint();
        explicitRevocation();
        hostHandoffAndStaleCallbacks();
        lateTimersAndRepeatedSwitches();
        invalidClockAndLimits();
        System.out.println("NotesGateGraceTest: " + checks + " assertions passed");
    }

    private static void graceBoundary() {
        NotesGateState before = unlocked();
        before.onBackground(1_000, 30_000);
        check(!before.isUnlocked(), "background content is hidden immediately");
        check(before.hasValidSession(1_000), "authentication grant survives in grace");
        check(before.remainingGraceMs(1_000) == 30_000, "default 30 second grace");
        check(before.remainingGraceMs(30_999) == 1, "last millisecond remains valid");
        check(before.resumeSession(30_999, true), "return before deadline needs no fingerprint");
        check(before.isUnlocked() && before.remainingGraceMs(31_000) == 0,
                "resumed session is visible and no longer runs a background deadline");

        NotesGateState equal = unlocked();
        equal.onBackground(1_000, 30_000);
        check(!equal.resumeSession(31_000, true), "exact deadline requires fingerprint");
        check(!equal.isUnlocked() && !equal.hasValidSession(31_001), "expiry is permanent");
        NotesGateState after = unlocked();
        after.onBackground(1_000, 30_000);
        check(!after.resumeSession(31_001, true), "return after deadline requires fingerprint");
        check(after.remainingGraceMs(31_002) == 0, "expired grace never becomes negative");
        NotesGateState immediate = unlocked();
        immediate.onBackground(1_000, 0);
        check(!immediate.hasValidSession(1_000) && !immediate.resumeSession(1_000, true),
                "zero delay immediately and permanently revokes the grant");
    }

    private static void duplicateLifecycleEvents() {
        NotesGateState gate = unlocked();
        gate.onBackground(100, 30_000); // explicit LaunchActivity onPause
        gate.onBackground(110, 30_000); // Application callback
        gate.onBackground(250, 30_000); // onStop
        check(gate.remainingGraceMs(250) == 29_850, "pause/pause/stop use first transition time");
        gate.onBackground(30_099, NotesGateState.MAX_GRACE_MS);
        check(gate.remainingGraceMs(30_099) == 1, "later configuration cannot extend this background grant");
        gate.onBackground(30_100, NotesGateState.MAX_GRACE_MS);
        check(!gate.hasValidSession(30_100), "late duplicate expires instead of renewing");
        check(!gate.resumeSession(30_101, true), "lifecycle events cannot create a new grant");
    }

    private static void noGrantWithoutFingerprint() {
        NotesGateState gate = new NotesGateState();
        check(!gate.resumeSession(100, true), "cold process cannot auto-resume");
        long host = gate.attachHost();
        gate.onResume(host, true);
        long failed = gate.beginAuthentication(host);
        check(failed != 0, "deliberate scan begins");
        gate.authenticationEnded(host, failed);
        gate.onBackground(200, 30_000);
        check(!gate.hasValidSession(201), "failed fingerprint has no grace");
        check(!gate.tryUnlock(host, failed), "failed callback cannot authorize later");
        long pending = gate.beginAuthentication(host);
        gate.onBackground(300, 30_000);
        check(!gate.tryUnlock(host, pending), "background invalidates pending fingerprint proof");
        check(!gate.resumeSession(301, true), "cancelled proof never gains a grace session");
    }

    private static void explicitRevocation() {
        NotesGateState gate = unlocked();
        gate.onBackground(100, 30_000);
        gate.onScreenOff();
        check(!gate.resumeSession(101, true), "screen-on cannot revive revoked grace");
        NotesGateState explicit = unlocked();
        explicit.onBackground(100, 30_000);
        explicit.lock();
        check(!explicit.hasValidSession(101) && !explicit.resumeSession(102, true),
                "manual lock permanently revokes grace");
        NotesGateState screen = unlocked();
        screen.onBackground(100, 30_000);
        check(!screen.resumeSession(101, false), "return with display/keyguard unavailable revokes");
        check(!screen.resumeSession(102, true), "later display readiness cannot restore revoked grant");
        NotesGateState restarted = new NotesGateState();
        check(!restarted.resumeSession(101, true), "new process has no grace from prior process");
    }

    private static void hostHandoffAndStaleCallbacks() {
        NotesGateState gate = new NotesGateState();
        long oldHost = gate.attachHost();
        gate.onResume(oldHost, true);
        long proof = gate.beginAuthentication(oldHost);
        check(gate.tryUnlock(oldHost, proof), "current proof creates foreground grant");
        gate.onPause(oldHost);
        gate.detach(oldHost);
        check(gate.isUnlocked(), "successful notes handoff keeps foreground grant");
        gate.onBackground(100, 30_000);
        long launcherHost = gate.attachHost();
        gate.onResume(launcherHost, true);
        gate.onPause(oldHost);
        gate.detach(oldHost);
        check(gate.resumeSession(101, true), "desktop-created notes host can resume the same valid process session");
        check(!gate.tryUnlock(oldHost, proof), "old proof remains consumed after auto-resume");
        gate.onBackground(200, 30_000);
        check(!gate.tryUnlock(launcherHost, proof), "wrong host proof never resumes grace");
        check(!gate.isUnlocked(), "stale proof leaves background content covered");
        gate.lock();
        check(gate.beginAuthentication(launcherHost) != 0, "fresh fingerprint is available after explicit revocation");
    }

    private static void lateTimersAndRepeatedSwitches() {
        NotesGateState gate = unlocked();
        gate.onBackground(1_000, 30_000);
        check(gate.resumeSession(2_000, true), "first short return auto-resumes");
        check(gate.hasValidSession(31_000) && gate.isUnlocked(),
                "late old expiry timer cannot revoke a resumed foreground session");
        gate.onBackground(40_000, 30_000);
        check(gate.hasValidSession(41_000) && gate.remainingGraceMs(41_000) == 29_000,
                "old callback during next background uses the new deadline");
        check(gate.resumeSession(69_999, true), "second exit receives its own grace");
        check(gate.hasValidSession(70_000) && gate.isUnlocked(), "second stale timer also preserves foreground");
        gate.onBackground(80_000, 30_000);
        check(!gate.hasValidSession(110_000), "current timer expires current background grant");
        long host = gate.attachHost();
        gate.onResume(host, true);
        long attempt = gate.beginAuthentication(host);
        check(gate.tryUnlock(host, attempt), "new fingerprint grants a fresh session after expiry");
        check(gate.hasValidSession(120_000) && gate.isUnlocked(), "old timer cannot clear a new authenticated grant");

        long now = 130_000;
        for (int i = 0; i < 20; i++) {
            gate.onBackground(now, 2_000);
            gate.onBackground(now + 10, 2_000);
            check(gate.remainingGraceMs(now + 10) == 1_990, "repeated short exits keep each first deadline");
            check(gate.resumeSession(now + 500, true), "each short return can resume current grant");
            check(gate.hasValidSession(now + 2_000) && gate.isUnlocked(), "stale timer does not revoke short-return session");
            now += 3_000;
        }
    }

    private static void invalidClockAndLimits() {
        NotesGateState backwards = unlocked();
        backwards.onBackground(10_000, 30_000);
        check(!backwards.resumeSession(9_999, true), "monotonic time regression fails closed");
        check(!backwards.resumeSession(10_001, true), "clock correction cannot revive session");
        NotesGateState foreground = unlocked();
        check(foreground.hasValidSession(1_000), "foreground establishes clock baseline");
        check(!foreground.hasValidSession(999) && !foreground.isUnlocked(), "foreground time regression also fails closed");
        for (long invalid : new long[] {-1, NotesGateState.MAX_GRACE_MS + 1, Long.MAX_VALUE}) {
            NotesGateState gate = unlocked();
            gate.onBackground(100, invalid);
            check(!gate.hasValidSession(101), "invalid grace does not retain authorization");
        }
        NotesGateState negativeClock = unlocked();
        negativeClock.onBackground(-1, 30_000);
        check(!negativeClock.hasValidSession(0), "negative monotonic time fails closed");
        NotesGateState overflow = unlocked();
        overflow.onBackground(Long.MAX_VALUE - 1, 2);
        check(!overflow.hasValidSession(Long.MAX_VALUE), "deadline overflow cannot extend access");
        NotesGateState maximum = unlocked();
        maximum.onBackground(1, NotesGateState.MAX_GRACE_MS);
        check(maximum.remainingGraceMs(1) == 86_400_000, "maximum is one day in milliseconds");
        check(maximum.resumeSession(86_400_000, true), "last millisecond of maximum grace remains valid");
    }

    private static NotesGateState unlocked() {
        NotesGateState gate = new NotesGateState();
        long host = gate.attachHost();
        gate.onResume(host, true);
        check(gate.tryUnlock(host, gate.beginAuthentication(host)), "fixture authenticates through real state transition");
        return gate;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
