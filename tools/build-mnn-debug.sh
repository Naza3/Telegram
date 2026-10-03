#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
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

if [[ ! -f "$task_dir/debug.keystore" ]]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$task_dir/debug.keystore" \
        -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
fi

cd "$repo_dir"
./gradlew :TMessagesProj_App:assembleAfatDebug --no-daemon \
    --no-build-cache --no-configuration-cache \
    --max-workers="${MNN_BUILD_WORKERS:-3}" --console=plain \
    -I "$repo_dir/tools/mnn-debug.init.gradle" \
    -DmnnDebugKeystore="$task_dir/debug.keystore" \
    -PmnnAbi="${MNN_BUILD_ABI:-arm64-v8a}" "$@"

echo "APK directory: $repo_dir/TMessagesProj_App/build/outputs/apk/afat/debug"
