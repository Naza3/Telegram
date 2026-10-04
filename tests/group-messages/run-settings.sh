#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
ecj_jar="${ECJ_JAR:-$tools_dir/ecj.jar}"
json_jar="${JSON_JAR:-$tools_dir/json.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-group-settings-tests.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
mapfile -d '' stub_files < <(find "$test_dir/settings-stubs" -name '*.java' -print0)
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -cp "$json_jar" -d "$classes_dir" \
    "${stub_files[@]}" "$test_dir/GroupMessageSettingsTest.java" \
    "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages/GroupMessageSettings.java"
java -cp "$classes_dir:$json_jar" org.telegram.messenger.groupmessages.GroupMessageSettingsTest
