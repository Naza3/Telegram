#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../../.." && pwd)"
ecj_jar="${ECJ_JAR:-/workspace/ai-summary-tools/ecj.jar}"
if [[ ! -r "$ecj_jar" ]]; then
    echo 'Set ECJ_JAR to an existing Eclipse ECJ compiler jar.' >&2
    exit 2
fi
classes="$(mktemp -d "${TMPDIR:-/tmp}/shiye-notes-preferences.XXXXXX")"
trap 'rm -rf -- "$classes"' EXIT
mapfile -t sources < <(find "$test_dir" -name '*.java' -print)
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -d "$classes" \
    "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/NotesEntryPreferences.java" "${sources[@]}"
java -cp "$classes" org.telegram.messenger.NotesEntryPreferencesTest
