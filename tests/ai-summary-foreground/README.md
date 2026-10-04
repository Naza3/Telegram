# AI summary foreground service lifecycle tests

Run `bash tests/ai-summary-foreground/run.sh` with Java 17+ on `PATH` and an
existing `ECJ_JAR` (default: `/workspace/ai-summary-tools/ecj.jar`). The runner
downloads nothing and deletes its temporary classes after every run.

This suite executes the production `SummaryForegroundService.java`. Android
service dispatch, clocks, handlers, notifications, wake locks, and account
state are deterministic boundary fakes. It verifies explicit foreground
startup, owner/task identity, cancellation and cleanup, bounded wake locks,
the two-hour deadline, Android 15 timeout handling, notification privacy and
throttling, startup failures, and independent tasks in multiple accounts.

It does not run Android system services or prove behavior under OEM power
management. The service also needs compilation against the app's Android SDK
and AndroidX dependencies, and device checks with screen off, another app in
front, logout, user cancellation, and system service timeout.
