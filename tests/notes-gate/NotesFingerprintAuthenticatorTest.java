/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class NotesFingerprintAuthenticatorTest {
    private static int checks;
    public static void main(String[] args) {
        availabilityFailsClosed();
        terminalAndNonterminalEvents();
        cancelledAndReplacedCallbacks();
        startupAndCancelExceptions();
        gateAndDriverLifecycle();
        System.out.println("NotesFingerprintAuthenticatorTest: " + checks + " assertions passed");
    }
    private static void availabilityFailsClosed() {
        for (NotesFingerprintAuthenticator.Availability available : NotesFingerprintAuthenticator.Availability.values()) {
            Harness h = new Harness();
            h.driver.available = available;
            h.auth.authenticate(h.result);
            check(h.result.events.isEmpty(), "even availability errors dispatch through UI boundary");
            h.ui.drain();
            if (available == NotesFingerprintAuthenticator.Availability.AVAILABLE) {
                check(h.driver.starts.size() == 1 && h.result.events.isEmpty(), "available starts exactly one scan");
            } else {
                check(h.driver.starts.isEmpty(), "unsupported/unavailable does not start hardware");
                check(h.result.events.size() == 1 && h.result.events.get(0).startsWith("error:"), "unavailable ends with error");
            }
        }
        Harness h = new Harness();
        h.driver.availabilityThrows = true;
        check(h.auth.availability() == NotesFingerprintAuthenticator.Availability.UNAVAILABLE, "query exceptions fail closed");
        h.auth.authenticate(h.result);
        h.ui.drain();
        check(h.driver.starts.isEmpty() && h.result.events.size() == 1, "throwing query cannot grant access");
    }
    private static void terminalAndNonterminalEvents() {
        Harness h = new Harness();
        h.auth.authenticate(h.result);
        NotesFingerprintAuthenticator.Callback scan = h.driver.starts.get(0);
        scan.onHelp("try again");
        scan.onHelp("not matched");
        h.ui.drain();
        check(h.result.events.size() == 2, "help/failed match remains same active attempt");
        scan.onSuccess();
        scan.onSuccess();
        scan.onError("late error");
        scan.onHelp("late help");
        h.ui.drain();
        check(h.result.events.size() == 3 && h.result.events.get(2).equals("success"), "terminal success consumed once");
        h.auth.authenticate(h.result);
        scan = h.driver.starts.get(1);
        scan.onError(null);
        scan.onSuccess();
        h.ui.drain();
        check(h.result.events.size() == 4 && h.result.events.get(3).startsWith("error:"), "terminal error cannot be followed by success");
    }
    private static void cancelledAndReplacedCallbacks() {
        Harness h = new Harness();
        h.auth.authenticate(h.result);
        NotesFingerprintAuthenticator.Callback a = h.driver.starts.get(0);
        a.onSuccess();
        h.auth.cancel();
        h.ui.drain();
        check(h.result.events.isEmpty(), "cancel suppresses queued success");
        a.onSuccess();
        a.onError("late cancellation");
        a.onHelp("late hint");
        h.ui.drain();
        check(h.result.events.isEmpty(), "all callbacks suppressed after cancellation");
        h.auth.authenticate(h.result);
        NotesFingerprintAuthenticator.Callback b = h.driver.starts.get(1);
        b.onSuccess();
        h.auth.authenticate(h.result);
        NotesFingerprintAuthenticator.Callback c = h.driver.starts.get(2);
        a.onSuccess();
        b.onSuccess();
        c.onHelp("current");
        c.onSuccess();
        h.ui.drain();
        check(h.result.events.size() == 2 && h.result.events.get(0).equals("help:current")
                && h.result.events.get(1).equals("success"), "only latest generation reaches caller");
    }
    private static void startupAndCancelExceptions() {
        Harness h = new Harness();
        h.driver.startThrows = true;
        h.auth.authenticate(h.result);
        h.ui.drain();
        check(h.result.events.size() == 1 && h.result.events.get(0).startsWith("error:"), "startup exception ends visibly");
        h.driver.startThrows = false;
        h.auth.authenticate(h.result);
        NotesFingerprintAuthenticator.Callback scan = h.driver.starts.get(0);
        h.driver.cancelCallback = scan;
        h.driver.cancelThrows = true;
        h.auth.cancel();
        h.ui.drain();
        check(h.result.events.size() == 1, "synchronous success during throwing cancel stays suppressed");
        scan.onSuccess();
        h.ui.drain();
        check(h.result.events.size() == 1, "cancel exception does not revive attempt");
    }
    private static void gateAndDriverLifecycle() {
        Harness h = new Harness();
        NotesGateState gate = new NotesGateState();
        long host = gate.attachHost();
        gate.onResume(host, true);
        long attempt = gate.beginAuthentication(host);
        Result result = new Result() {
            @Override public void onSuccess() {
                check(!gate.tryUnlock(host, attempt), "lifecycle state is checked even before hardware cancellation runs");
                super.onSuccess();
            }
        };
        h.auth.authenticate(result);
        h.driver.starts.get(0).onSuccess();
        gate.onPause(host);
        h.ui.drain();
        check(!gate.isUnlocked(), "paused host cannot open Telegram");
    }
    private static final class Harness {
        final FakeDriver driver = new FakeDriver();
        final Queue ui = new Queue();
        final Result result = new Result();
        final NotesFingerprintAuthenticator auth = new NotesFingerprintAuthenticator(driver, ui);
    }
    private static final class Queue implements NotesFingerprintAuthenticator.Dispatcher {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void post(Runnable runnable) { pending.add(runnable); }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
    private static class Result implements NotesFingerprintAuthenticator.Callback {
        final List<String> events = new ArrayList<>();
        @Override public void onSuccess() { events.add("success"); }
        @Override public void onError(String message) { events.add("error:" + message); }
        @Override public void onHelp(String message) { events.add("help:" + message); }
    }
    private static final class FakeDriver implements NotesFingerprintAuthenticator.Driver {
        final List<NotesFingerprintAuthenticator.Callback> starts = new ArrayList<>();
        NotesFingerprintAuthenticator.Availability available = NotesFingerprintAuthenticator.Availability.AVAILABLE;
        boolean availabilityThrows, startThrows, cancelThrows;
        NotesFingerprintAuthenticator.Callback cancelCallback;
        @Override public NotesFingerprintAuthenticator.Availability availability() {
            if (availabilityThrows) throw new SecurityException();
            return available;
        }
        @Override public void authenticate(NotesFingerprintAuthenticator.Callback callback) {
            if (startThrows) throw new SecurityException();
            starts.add(callback);
        }
        @Override public void cancel() {
            if (cancelCallback != null) cancelCallback.onSuccess();
            if (cancelThrows) throw new IllegalStateException();
        }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
