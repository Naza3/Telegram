#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
ecj_jar="${ECJ_JAR:-$tools_dir/ecj.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-summary-foreground.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT

if [[ ! -r "$ecj_jar" ]]; then
    echo "Set ECJ_JAR to an existing Eclipse ECJ compiler jar." >&2
    exit 2
fi

mapfile -d '' stub_files < <(find "$test_dir/stubs" -name '*.java' -print0)
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -d "$classes_dir" \
    "${stub_files[@]}" \
    "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/SummaryForegroundService.java" \
    "$test_dir/SummaryForegroundServiceTest.java"
java -cp "$classes_dir" org.telegram.messenger.SummaryForegroundServiceTest
