#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# Public version values are validated again in the init script for direct Gradle invocation.
python3 - "$repo_dir/gradle.properties" <<'PY'
import os
import re
import sys
from pathlib import Path

name = os.environ.get('MNN_RELEASE_VERSION_NAME', '')
code = os.environ.get('MNN_RELEASE_VERSION_CODE', '')
component = r'(?:0|[1-9][0-9]{0,5})'
if (not re.fullmatch(r'[1-9][0-9]{0,8}', code)
        or int(code) > 209999999
        or not re.fullmatch(rf'{component}\.{component}\.{component}-mnn\.{code}', name)):
    sys.exit('Set matching MNN_RELEASE_VERSION_NAME (major.minor.patch-mnn.baseCode) and MNN_RELEASE_VERSION_CODE (positive baseCode).')
properties = dict(line.split('=', 1) for line in Path(sys.argv[1]).read_text().splitlines()
                  if line.startswith(('APP_VERSION_NAME=', 'APP_VERSION_CODE=')))
if code != properties.get('APP_VERSION_CODE') or name != properties.get('APP_VERSION_NAME', '') + '-mnn.' + code:
    sys.exit('Release version values must match APP_VERSION_NAME and APP_VERSION_CODE in gradle.properties.')
PY
python3 "$repo_dir/tools/check-telegram-api.py"

task_dir="${MNN_BUILD_DIR:-$repo_dir/.local-build}"
mkdir -p "$task_dir"
task_dir="$(cd -- "$task_dir" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$task_dir/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_dir/gradle-user}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/javac" || ! -d "$ANDROID_HOME/platforms/android-36" ]]; then
    echo 'Set JAVA_HOME to a JDK 17+ and ANDROID_HOME to an SDK containing Android 36.' >&2
    exit 2
fi
if [[ -z "${ANDROID_SIGNING_KEYSTORE_PATH:-}" || "$ANDROID_SIGNING_KEYSTORE_PATH" != /*
        || ! -f "$ANDROID_SIGNING_KEYSTORE_PATH" || ! -r "$ANDROID_SIGNING_KEYSTORE_PATH"
        || ! -s "$ANDROID_SIGNING_KEYSTORE_PATH" ]]; then
    echo 'Set ANDROID_SIGNING_KEYSTORE_PATH to the absolute path of the original key prepared by tools/prepare-android-signing.py.' >&2
    exit 2
fi
for signing_name in ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    if [[ -z "${!signing_name:-}" ]]; then
        echo "Missing signing setting: $signing_name" >&2
        exit 2
    fi
done

# Resource/diagnostic options are allowed; task, init-script and project identity overrides are not.
for option in "$@"; do
    case "$option" in
        --offline|--dry-run|--stacktrace|--info|-Dorg.gradle.jvmargs=*|-Dorg.gradle.workers.max=*|-Dorg.gradle.parallel=false) ;;
        *) echo 'Unsupported release build option; use MNN_BUILD_WORKERS or Gradle JVM resource options.' >&2; exit 2 ;;
    esac
done

cd "$repo_dir"
./gradlew :TMessagesProj_App:assembleAfatRelease --no-daemon \
    --no-build-cache --no-configuration-cache \
    --max-workers="${MNN_BUILD_WORKERS:-3}" --console=plain \
    -I "$repo_dir/tools/mnn-release.init.gradle" "$@"

echo "APK directory: $repo_dir/TMessagesProj_App/build/outputs/apk/afat/release"
