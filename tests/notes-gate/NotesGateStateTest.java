/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

public final class NotesGateStateTest {
    private static int checks;
    public static void main(String[] args) {
        NotesGateState gate = new NotesGateState();
        check(!gate.isUnlocked(), "new process is locked");
        long firstHost = gate.attachHost();
        check(gate.beginAuthentication(firstHost) == 0, "not resumed");
        gate.onResume(firstHost, false);
        check(gate.beginAuthentication(firstHost) == 0, "screen is off");
        gate.onResume(firstHost, true);
        long firstAttempt = begin(gate, firstHost);
        check(gate.beginAuthentication(firstHost) == 0, "duplicate trigger refused");
        check(!gate.tryUnlock(firstHost + 1, firstAttempt), "wrong host cannot consume");
        check(!gate.tryUnlock(firstHost, firstAttempt + 1), "wrong attempt cannot consume");
        gate.authenticationEnded(firstHost + 1, firstAttempt);
        check(gate.isAuthenticating(), "old host failure cannot end current scan");
        gate.authenticationEnded(firstHost, firstAttempt);
        check(!gate.tryUnlock(firstHost, firstAttempt), "ended scan cannot unlock");
        long secondAttempt = begin(gate, firstHost);
        gate.authenticationEnded(firstHost, firstAttempt);
        check(gate.isAuthenticating(), "late old failure cannot end retry");
        gate.onPause(firstHost);
        check(!gate.tryUnlock(firstHost, secondAttempt), "pause cancels authorization");
        gate.onResume(firstHost, true);
        check(!gate.tryUnlock(firstHost, secondAttempt), "resume does not revive old attempt");
        check(!gate.isUnlocked(), "resume does not unlock");
        long screenAttempt = begin(gate, firstHost);
        gate.onScreenOff();
        check(!gate.tryUnlock(firstHost, screenAttempt), "screen off cancels authorization");
        check(gate.beginAuthentication(firstHost) == 0, "no auth while screen off");
        gate.onResume(firstHost, true);
        long detachedAttempt = begin(gate, firstHost);
        gate.detach(firstHost);
        check(!gate.tryUnlock(firstHost, detachedAttempt), "destroyed host cannot unlock");
        gate.onResume(firstHost, true);
        check(gate.beginAuthentication(firstHost) == 0, "detached host cannot resume itself");
        long secondHost = gate.attachHost();
        gate.onResume(secondHost, true);
        long replacementAttempt = begin(gate, secondHost);
        long thirdHost = gate.attachHost();
        gate.onResume(thirdHost, true);
        check(!gate.tryUnlock(secondHost, replacementAttempt), "host replacement invalidates success");
        long acceptedAttempt = begin(gate, thirdHost);
        gate.onPause(secondHost);
        gate.detach(secondHost);
        gate.onResume(secondHost, false);
        check(gate.tryUnlock(thirdHost, acceptedAttempt), "old lifecycle cannot affect current host");
        check(gate.isUnlocked(), "current authorized fingerprint opens process session");
        check(!gate.tryUnlock(thirdHost, acceptedAttempt), "success is one-shot");
        check(gate.beginAuthentication(thirdHost) == 0, "unlocked session cannot scan");
        gate.onPause(thirdHost);
        gate.detach(thirdHost);
        check(gate.isUnlocked(), "successful handoff survives notes pause/detach");
        gate.lock();
        check(!gate.isUnlocked(), "facade background locks handoff session");
        check(!gate.tryUnlock(thirdHost, acceptedAttempt), "lock never resurrects accepted token");
        long fourthHost = gate.attachHost();
        gate.onResume(fourthHost, true);
        long afterBackground = begin(gate, fourthHost);
        gate.lock();
        check(!gate.tryUnlock(fourthHost, afterBackground), "lock invalidates an active scan");
        check(gate.tryUnlock(fourthHost, begin(gate, fourthHost)), "new deliberate attempt works after lock");
        gate.onScreenOff();
        check(!gate.isUnlocked(), "screen off locks already unlocked session");
        check(!new NotesGateState().isUnlocked(), "no unlocked state persists into new instance");
        System.out.println("NotesGateStateTest: " + checks + " assertions passed");
    }
    private static long begin(NotesGateState gate, long host) {
        long result = gate.beginAuthentication(host);
        check(result > 0 && gate.isAuthenticating(), "valid scan begins");
        return result;
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
