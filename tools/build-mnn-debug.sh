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

signing_path="${ANDROID_SIGNING_KEYSTORE_PATH:-$task_dir/signing/current.keystore}"
if [[ ! -s "$signing_path" ]]; then
    echo 'Prepare the original signing key with tools/prepare-android-signing.py before building.' >&2
    exit 2
fi
for signing_name in ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    if [[ -z "${!signing_name:-}" ]]; then
        echo "Missing signing setting: $signing_name" >&2
        exit 2
    fi
done

cd "$repo_dir"
./gradlew :TMessagesProj_App:assembleAfatDebug --no-daemon \
    --no-build-cache --no-configuration-cache \
    --max-workers="${MNN_BUILD_WORKERS:-3}" --console=plain \
    -I "$repo_dir/tools/mnn-debug.init.gradle" \
    -DmnnDebugKeystore="$signing_path" \
    -PmnnAbi="${MNN_BUILD_ABI:-arm64-v8a}" "$@"

echo "APK directory: $repo_dir/TMessagesProj_App/build/outputs/apk/afat/debug"
