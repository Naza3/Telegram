# Notes entry preferences tests

Run `bash tests/notes-gate/preferences/run.sh`; set `ECJ_JAR` if the existing ECJ compiler is elsewhere. This standalone JVM suite compiles the real `NotesEntryPreferences` source against small Android preference stubs, without Gradle, network, or the main notes-gate runner.

Coverage includes the 30-second default, integer bounds 0–86400, invalid stored types/values, validation before editing, background commit results, failed-commit memory rollback (including a second failed rollback commit), a blocked commit whose early memory update must remain invisible to getters, and the existing trigger API. The stub models separate memory and disk maps because Android can mutate preference memory even when `commit()` returns false.
