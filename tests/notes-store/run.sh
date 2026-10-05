#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
ecj_jar="${ECJ_JAR:-$tools_dir/ecj.jar}"
json_jar="${JSON_JAR:-$tools_dir/json.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/shiye-notes-tests.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
if [[ ! -r "$ecj_jar" || ! -r "$json_jar" ]]; then
    echo "Set ECJ_JAR and JSON_JAR; see tests/ai-summary/bootstrap-tools.sh." >&2
    exit 2
fi
mapfile -t sources < <(find "$test_dir" -name '*.java' -print)
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -cp "$json_jar" -d "$classes_dir" \
    "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/NotesStore.java" "${sources[@]}"
java -cp "$classes_dir:$json_jar" org.telegram.messenger.NotesStoreTest
