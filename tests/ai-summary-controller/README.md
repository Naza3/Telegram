# Summary controller lifecycle regression

Run with JDK 17+ (`java` on `PATH`) and the same pinned ECJ compiler and JVM JSON
jar as the existing AI harness. The runner does not download tools:

```sh
ECJ_JAR=/path/to/ecj.jar JSON_JAR=/path/to/json.jar bash tests/ai-summary-controller/run.sh
```

`AI_SUMMARY_TOOLS_DIR/{ecj.jar,json.jar}` is also supported. In the prepared workspace:

```sh
JAVA_HOME=/workspace/toolchains/jdk-21 \
PATH=/workspace/toolchains/jdk-21/bin:$PATH \
bash tests/ai-summary-controller/run.sh
```

The runner compiles the **actual production** `SummaryTaskController`,
`SummaryTaskCheckpoint`, `SummaryStateStore`, `SummaryMessage`,
`SummarySourceReference`, `SummaryFilter` and `PromptOptions`. It does not copy the
controller state machine. Each scenario controls external callback delivery and
background/UI queue order, including deliberately late callbacks.

The private `stubs/` tree replaces Android scheduling/notifications, Telegram
peer lookup, loader/model network I/O, archive storage and the foreground-service
boundary. These fakes record calls and expose failures. Storage tests execute the
real checkpoint and cursor implementation against an in-memory SharedPreferences
boundary. The archive fake does **not** test AndroidKeyStore or file encryption.
The RangeRequest stub is the sheet's data shape, with no View implementation.
Reflection only resets test listeners between cases and checks weak references.
No shared files under `tests/ai-summary/stubs/` are modified by this harness.

Coverage includes detached listeners/reentry without duplicate requests,
cancellation at loading/generating/queued-save stages, old callbacks and expiry,
account/owner changes, source edits/deletes/access revocation, checkpoint task-ID
fencing, archive failure versus cursor advancement, stale archive retries, partial
coverage/replay/empty batches, foreground-service refusal/failure/stop callbacks,
and bounded request-input retention. Reentrant listener cancellation is exercised.
Additional cases cover bounded and deduplicated terminal request metrics, late
metrics after cancellation/account replacement, UID filtering before inference,
selected snapshots with no history RPC or generic completion/cursor write, and
same-text changes to sender/topic/date/reply metadata. The selected-snapshot
factory itself is tested in the separate core harness.

Specified-date cases verify dispatch to the date loader, exact date labels in
archived results and snapshot replays, preserving an existing incremental cursor,
partial and empty days, and retaining the chosen date after cancellation.

Passing this harness establishes controller decisions and boundary calls. It does
not establish actual Android service startup, notification/wake-lock behavior,
process death recovery, native Telegram RPC access, model quality, or device
background survival. Those require the separate service harness and Android/device
checks. No model service is contacted and all chat text is synthetic.
