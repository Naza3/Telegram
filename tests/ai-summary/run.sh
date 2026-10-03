#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
ecj_jar="${ECJ_JAR:-$tools_dir/ecj.jar}"
json_jar="${JSON_JAR:-$tools_dir/json.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-ai-summary-tests.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT

if [[ ! -r "$ecj_jar" || ! -r "$json_jar" ]]; then
    echo "Set ECJ_JAR and JSON_JAR to an Eclipse ECJ compiler and a JVM org.json jar." >&2
    exit 2
fi

mapfile -d '' stub_files < <(find "$test_dir/stubs" -name '*.java' -print0)
mapfile -d '' test_files < <(find "$test_dir" -maxdepth 1 -name '*Test.java' -print0)
production_dir="$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/ai"
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -cp "$json_jar" -d "$classes_dir" \
    "${stub_files[@]}" "${test_files[@]}" \
    "$production_dir/SummaryMessage.java" \
    "$production_dir/SummaryHistoryLoader.java" \
    "$production_dir/AiSummarySettings.java" \
    "$production_dir/AiSummaryPrompt.java" \
    "$production_dir/AiSummaryClient.java"

for test_file in "${test_files[@]}"; do
    test_class="$(basename -- "$test_file" .java)"
    java --add-modules jdk.httpserver -cp "$classes_dir:$json_jar" "org.telegram.messenger.ai.$test_class"
done
