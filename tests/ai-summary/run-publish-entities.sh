#!/usr/bin/env bash
set -euo pipefail

# This suite uses real compiled TLRPC/SerializedData, not the pure-core TL stubs.
# Run after the Android library Java build, or point TLRPC_CLASSES_JAR at its jar.
test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../.." && pwd)"
native_jar="${TLRPC_CLASSES_JAR:-$repo_dir/TMessagesProj/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar}"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-summary-entities.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT

if [[ ! -r "$native_jar" ]]; then
    echo "Build the Telegram Android library first, or set TLRPC_CLASSES_JAR to its compiled classes.jar." >&2
    exit 2
fi

# The native wire code only touches these app services on logging/error paths.
# Host shims suppress app logging and UI posting; all TL classes and wire readers
# and writers remain the real compiled production implementations.
mkdir -p "$classes_dir/shims/org/telegram/messenger"
cat > "$classes_dir/shims/org/telegram/messenger/BuildVars.java" <<'JAVA'
package org.telegram.messenger;
public final class BuildVars { public static boolean LOGS_ENABLED = false; }
JAVA
cat > "$classes_dir/shims/org/telegram/messenger/FileLog.java" <<'JAVA'
package org.telegram.messenger;
public final class FileLog {
    public static void e(Throwable value) { }
    public static void e(Throwable value, boolean remote) { }
    public static void e(String value) { }
    public static void e(String value, Throwable error) { }
    public static void d(String value) { }
}
JAVA
cat > "$classes_dir/shims/org/telegram/messenger/AndroidUtilities.java" <<'JAVA'
package org.telegram.messenger;
public final class AndroidUtilities { public static void runOnUIThread(Runnable ignored) { } }
JAVA

production_dir="$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/ai"
javac --release 17 -encoding UTF-8 -cp "$native_jar" -d "$classes_dir" \
    "$classes_dir/shims/org/telegram/messenger/BuildVars.java" \
    "$classes_dir/shims/org/telegram/messenger/FileLog.java" \
    "$classes_dir/shims/org/telegram/messenger/AndroidUtilities.java" \
    "$production_dir/SummaryTextSplitter.java" \
    "$production_dir/SummaryPublishPlan.java" \
    "$production_dir/SummaryPublishEntities.java" \
    "$test_dir/SummaryPublishEntitiesTest.java"
java -cp "$classes_dir:$native_jar" org.telegram.messenger.ai.SummaryPublishEntitiesTest
