#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
native_jar="${TLRPC_CLASSES_JAR:-$repo_dir/TMessagesProj/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-revoked-native.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
if [[ ! -r "$native_jar" ]]; then
  echo "Build the Telegram Android library or set TLRPC_CLASSES_JAR first." >&2
  exit 2
fi
production_dir="$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages"
javac --release 17 -encoding UTF-8 -cp "$native_jar" -d "$classes_dir" \
  "$production_dir/DeletedMessageEligibility.java" "$test_dir/DeletedMessageEligibilityTest.java"
java -cp "$classes_dir:$native_jar" org.telegram.messenger.groupmessages.DeletedMessageEligibilityTest
