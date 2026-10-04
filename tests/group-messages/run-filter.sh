#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
ecj_jar="${ECJ_JAR:-${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}/ecj.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-group-filter-tests.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
java -jar "$ecj_jar" -17 -encoding UTF-8 -warn:none -d "$classes_dir" \
    "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages/GroupMessageFilter.java" \
    "$test_dir/GroupMessageFilterTest.java"
java -cp "$classes_dir" org.telegram.messenger.groupmessages.GroupMessageFilterTest
