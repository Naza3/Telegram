# Local notes storage checks

Run `bash tests/notes-store/run.sh`. The runner uses the repository's standalone ECJ/JSON tools, configurable through `ECJ_JAR`, `JSON_JAR`, or `AI_SUMMARY_TOOLS_DIR`; it does not invoke Gradle or access the network.

The suite checks durable CRUD, fixed-ID upserts, serial save/delete/load ordering, UI-thread callbacks, immutable snapshots, strict schema validation, Unicode round trips, damaged/oversized files, all public size limits, and injected open/partial-write/finish/rename failures without losing committed notes. AtomicFile backup recovery and orphan first writes are covered.

Android framework classes are JVM test doubles. The JSON adapter exercises schema handling but is not an Android parser implementation, and the atomic file double cannot prove device filesystem/power-loss behavior. Verify the production source against the Android SDK and exercise the actual app separately.
