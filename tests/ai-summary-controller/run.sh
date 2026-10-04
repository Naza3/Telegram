#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
ecj_jar="${ECJ_JAR:-$tools_dir/ecj.jar}"
json_jar="${JSON_JAR:-$tools_dir/json.jar}"
if [[ ! -r "$ecj_jar" || ! -r "$json_jar" ]]; then
  echo 'Set ECJ_JAR and JSON_JAR or AI_SUMMARY_TOOLS_DIR to the existing pinned harness tools.' >&2
  exit 2
fi
classes="$(mktemp -d "${TMPDIR:-/tmp}/telegram-summary-controller.XXXXXX")"
trap 'rm -rf -- "$classes"' EXIT
mapfile -d '' stubs < <(find "$test_dir/stubs" -name '*.java' -print0)
production="$repo_dir/TMessagesProj/src/main/java"
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -cp "$json_jar" -d "$classes" "${stubs[@]}" \
  "$production/org/telegram/ui/Components/SummaryTaskController.java" \
  "$production/org/telegram/messenger/ai/SummaryTaskCheckpoint.java" \
  "$production/org/telegram/messenger/ai/SummaryStateStore.java" \
  "$production/org/telegram/messenger/ai/SummaryMessage.java" \
  "$production/org/telegram/messenger/ai/SummarySourceReference.java" \
  "$production/org/telegram/messenger/ai/SummaryFilter.java" \
  "$production/org/telegram/messenger/ai/PromptOptions.java" \
  "$test_dir/SummaryTaskControllerTest.java"
java -cp "$classes:$json_jar" org.telegram.ui.Components.SummaryTaskControllerTest
