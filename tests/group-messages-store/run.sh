#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
javac -encoding UTF-8 -source 8 -target 8 -d "$BUILD_DIR" \
  "$REPO_ROOT/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages/DeletedMessageRecord.java" \
  "$REPO_ROOT/TMessagesProj/src/main/java/org/telegram/messenger/groupmessages/DeletedMessageStore.java" \
  "$REPO_ROOT/tests/group-messages-store/DeletedMessageStoreTest.java"
java -cp "$BUILD_DIR" org.telegram.messenger.groupmessages.DeletedMessageStoreTest
