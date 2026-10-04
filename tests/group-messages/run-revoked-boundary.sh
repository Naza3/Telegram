#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
android_jar="${ANDROID_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platforms/android-36/android.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-revoked-boundary.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
if [[ ! -r "$android_jar" ]]; then
  echo "Set ANDROID_JAR or ANDROID_HOME to an installed Android SDK (API 36)." >&2
  exit 2
fi
production_dir="$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages"
mapfile -d '' stubs < <(find "$test_dir/revoked-stubs" -name '*.java' -print0)
javac --release 17 -encoding UTF-8 -cp "$android_jar" -d "$classes_dir" \
  "${stubs[@]}" "$production_dir/DeletedMessageRecord.java" "$production_dir/DeletedMessageStore.java" \
  "$production_dir/DeletedGroupMessages.java" "$test_dir/DeletedGroupMessagesBoundaryTest.java"
java -cp "$classes_dir:$android_jar" org.telegram.messenger.groupmessages.DeletedGroupMessagesBoundaryTest
