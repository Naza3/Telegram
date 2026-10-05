#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ecj_jar="${ECJ_JAR:-/workspace/ai-summary-tools/ecj.jar}"
android_jar="${ANDROID_JAR:-/workspace/android-sdk/platforms/android-35/android.jar}"
if [[ ! -r "$ecj_jar" || ! -r "$android_jar" ]]; then
  echo 'Set ECJ_JAR and ANDROID_JAR to existing compiler and Android platform jars.' >&2
  exit 2
fi
classes="$(mktemp -d "${TMPDIR:-/tmp}/telegram-notes-gate.XXXXXX")"
trap 'rm -rf -- "$classes"' EXIT
java -jar "$ecj_jar" -1.8 -encoding UTF-8 -warn:none -cp "$android_jar" -d "$classes" \
  "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/NotesGateState.java" \
  "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/NotesFingerprintAuthenticator.java" \
  "$repo_dir/tests/notes-gate/NotesGateStateTest.java" \
  "$repo_dir/tests/notes-gate/NotesGateGraceTest.java" \
  "$repo_dir/tests/notes-gate/NotesFingerprintAuthenticatorTest.java"
java -cp "$classes:$android_jar" org.telegram.messenger.NotesGateStateTest
java -cp "$classes:$android_jar" org.telegram.messenger.NotesGateGraceTest
java -cp "$classes:$android_jar" org.telegram.messenger.NotesFingerprintAuthenticatorTest
