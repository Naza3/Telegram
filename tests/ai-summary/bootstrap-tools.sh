#!/usr/bin/env bash
set -euo pipefail

# These are the same versions used by the JVM harness during local development.
# Do not execute a cached or downloaded jar before its pinned digest matches.
tools_dir="${AI_SUMMARY_TOOLS_DIR:-/workspace/ai-summary-tools}"
readonly ecj_version='3.39.0'
readonly ecj_sha256='01f5a92ac19bb2b3bf85e295a68f2c73c264369109158b566ce9b490af982948'
readonly json_version='20240303'
readonly json_sha256='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'

for required_command in curl sha256sum mktemp; do
    command -v "$required_command" >/dev/null || {
        printf 'Missing required tool: %s\n' "$required_command" >&2
        exit 2
    }
done

mkdir -p -- "$tools_dir"
download_dir="$(mktemp -d "$tools_dir/.download.XXXXXX")"
trap 'rm -rf -- "$download_dir"' EXIT

matches_digest() {
    local file="$1" expected="$2" actual
    [[ -f "$file" ]] || return 1
    actual="$(sha256sum -- "$file")"
    [[ "${actual%% *}" == "$expected" ]]
}

download_verified() {
    local name="$1" version="$2" artifact_path="$3" expected="$4"
    local destination="$tools_dir/$name.jar" candidate="$download_dir/$name.jar"
    if matches_digest "$destination" "$expected"; then
        printf 'Verified cached %s %s\n' "$name" "$version"
        return
    fi
    printf 'Downloading %s %s from Maven Central\n' "$name" "$version"
    curl --fail --silent --show-error --location \
        --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
        --output "$candidate" "https://repo.maven.apache.org/maven2/$artifact_path"
    if ! matches_digest "$candidate" "$expected"; then
        printf 'SHA256 verification failed for %s %s; dependency was not installed.\n' \
            "$name" "$version" >&2
        exit 1
    fi
    chmod 644 "$candidate"
    mv -f -- "$candidate" "$destination"
    printf 'Verified downloaded %s %s\n' "$name" "$version"
}

download_verified ecj "$ecj_version" \
    "org/eclipse/jdt/ecj/$ecj_version/ecj-$ecj_version.jar" "$ecj_sha256"
download_verified json "$json_version" \
    "org/json/json/$json_version/json-$json_version.jar" "$json_sha256"
