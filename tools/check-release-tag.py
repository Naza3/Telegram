#!/usr/bin/env python3
"""Derive bounded release versions from an environment-supplied tag, without shell evaluation."""

import argparse
import json
import os
from pathlib import Path
import re
import sys


MAX_BASE_CODE = 209999999  # Afat adds 9 after multiplying by 10; Android/Play limit is 2100000000.
COMPONENT = r"(?:0|[1-9][0-9]{0,5})"
TAG_PATTERN = re.compile(rf"mnn-v({COMPONENT}\.{COMPONENT}\.{COMPONENT})-([1-9][0-9]{{0,8}})")


class ReleaseTagError(ValueError):
    pass


def parse_tag(tag):
    if not isinstance(tag, str) or len(tag) > 64:
        raise ReleaseTagError("Release tag must match mnn-v<major.minor.patch>-<baseVersionCode>.")
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ReleaseTagError("Release tag must match mnn-v<major.minor.patch>-<baseVersionCode>.")
    version, digits = match.groups()
    code = int(digits)
    if code > MAX_BASE_CODE:
        raise ReleaseTagError("Release base version code exceeds the Android Afat limit.")
    return version, code


def baseline_code(properties):
    matches = re.findall(r"^[ \t]*APP_VERSION_CODE[ \t]*=[ \t]*([^\r\n]*)", properties, re.MULTILINE)
    if len(matches) != 1 or not re.fullmatch(r"[1-9][0-9]{0,8}[ \t]*", matches[0]):
        raise ReleaseTagError("gradle.properties must define one valid APP_VERSION_CODE.")
    code = int(matches[0])
    if code > MAX_BASE_CODE:
        raise ReleaseTagError("The source baseline exceeds the Android Afat limit.")
    return code


def source_version_name(properties):
    matches = re.findall(r"^[ \t]*APP_VERSION_NAME[ \t]*=[ \t]*([^\r\n]*)", properties, re.MULTILINE)
    if len(matches) != 1 or not re.fullmatch(rf"{COMPONENT}\.{COMPONENT}\.{COMPONENT}[ \t]*", matches[0]):
        raise ReleaseTagError("gradle.properties must define one valid APP_VERSION_NAME.")
    return matches[0].rstrip(" \t")


def validate_release(tag, properties, existing_tags=()):
    version, code = parse_tag(tag)
    baseline = baseline_code(properties)
    if code != baseline or version != source_version_name(properties):
        raise ReleaseTagError("Release tag version and code must match gradle.properties exactly.")
    for previous in existing_tags:
        if not previous.startswith("mnn-v"):
            continue
        _, previous_code = parse_tag(previous)
        if previous_code > code:
            raise ReleaseTagError("A newer mnn-v tag already reserves a higher version code.")
        if previous_code == code and previous != tag:
            raise ReleaseTagError("Another mnn-v tag already reserves this version code.")
    return {
        "MNN_RELEASE_TAG": tag,
        "MNN_RELEASE_VERSION_NAME": f"{version}-mnn.{code}",
        "MNN_RELEASE_VERSION_CODE": str(code),
        "MNN_RELEASE_APK_VERSION_CODE": str(code * 10 + 9),
    }


def read_bounded(path, maximum):
    with Path(path).open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ReleaseTagError("Release version metadata exceeds the supported size.")
    return data.decode("utf-8", errors="strict")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", default="gradle.properties")
    parser.add_argument("--tags-file", required=True, help="One fetched git tag per line; may be empty.")
    parser.add_argument("--github-env")
    parser.add_argument("--github-output")
    parser.add_argument("--json-output")
    args = parser.parse_args()
    try:
        tags = read_bounded(args.tags_file, 1024 * 1024).splitlines()
        if len(tags) > 10000:
            raise ReleaseTagError("Too many release tags to validate safely.")
        values = validate_release(os.environ.get("MNN_RELEASE_TAG_INPUT", ""),
                                  read_bounded(args.properties, 64 * 1024), tags)
        # Values contain only validated ASCII tokens. No untrusted multiline Actions commands.
        lines = "".join(f"{name}={value}\n" for name, value in values.items())
        for output in (args.github_env, args.github_output):
            if output:
                with Path(output).open("a", encoding="utf-8", newline="\n") as stream:
                    stream.write(lines)
        rendered = json.dumps(values, sort_keys=True, indent=2) + "\n"
        if args.json_output:
            Path(args.json_output).write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except ReleaseTagError as error:
        print(str(error), file=sys.stderr)
    except (OSError, UnicodeError):
        print("Cannot read or write release version metadata.", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
