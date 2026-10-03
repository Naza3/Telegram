#!/usr/bin/env python3
"""Validate synthetic source fixtures and optionally create an unrun review template.

Uses only the Python standard library. Never contacts a model or any network service.
Source IDs are checked for existence, not semantic entailment: a person must review
whether a model's actual claims and citations are supported by those sources.
"""

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


STATES = {"confirmed", "proposed", "corrected", "disputed", "unknown", "cancelled", "reported"}
REQUIRED_TAGS = {"negation", "correction", "disagreement", "handoff", "cross_chunk",
                 "prompt_injection", "no_conclusion"}


class InvalidFixture(ValueError):
    pass


def require(condition, where, description):
    if not condition:
        raise InvalidFixture(f"{where}: {description}")


def text(value, where):
    require(isinstance(value, str) and bool(value.strip()), where, "expected nonempty text")


def integer(value, where):
    require(type(value) is int, where, "expected integer, not boolean or string")


def object_fields(value, fields, where):
    require(isinstance(value, dict), where, "expected object")
    require(set(value) == set(fields), where, f"expected fields {sorted(fields)}")


def timestamp(value, where):
    text(value, where)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise InvalidFixture(f"{where}: invalid ISO 8601 timestamp") from error
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None,
            where, "timestamp must include its UTC offset")
    return parsed


def sources(value, ids, where, allow_empty=False):
    require(isinstance(value, list), where, "expected list of message IDs")
    require(allow_empty or bool(value), where, "at least one supporting source is required")
    for mid in value:
        integer(mid, where)
        require(mid in ids, where, f"source message {mid} does not exist")
    require(len(value) == len(set(value)), where, "duplicate source IDs")


def validate_fixture(data, filename):
    where = str(filename)
    object_fields(data, {"schema_version", "case_id", "title", "synthetic", "tags", "scope",
                         "messages", "expected", "review_notes"}, where)
    require(type(data["schema_version"]) is int and data["schema_version"] == 1,
            where, "unsupported schema_version")
    require(data["synthetic"] is True, where, "fixtures must explicitly be synthetic")
    require(isinstance(data["case_id"], str)
            and re.fullmatch(r"[0-9]{2}_[a-z0-9_]+", data["case_id"]) is not None,
            where, "invalid case_id")
    require(Path(filename).stem == data["case_id"], where, "filename and case_id differ")
    text(data["title"], where + ".title")
    require(isinstance(data["tags"], list) and bool(data["tags"]), where, "tags must be a nonempty list")
    for tag in data["tags"]:
        text(tag, where + ".tags")
    require(len(data["tags"]) == len(set(data["tags"])), where, "duplicate tags")

    scope = data["scope"]
    object_fields(scope, {"dialog_id", "topic_id", "timezone", "snapshot_at", "description"}, where + ".scope")
    integer(scope["dialog_id"], where + ".scope.dialog_id")
    require(scope["dialog_id"] < 0, where, "dialog_id must be a synthetic group ID")
    integer(scope["topic_id"], where + ".scope.topic_id")
    require(0 <= scope["topic_id"] <= 2**31 - 1, where, "invalid topic_id")
    text(scope["timezone"], where + ".scope.timezone")
    text(scope["description"], where + ".scope.description")
    snapshot = timestamp(scope["snapshot_at"], where + ".scope.snapshot_at")

    messages = data["messages"]
    require(isinstance(messages, list) and bool(messages), where, "messages must be a nonempty list")
    ids = set()
    previous = None
    for index, message in enumerate(messages):
        at = f"{where}.messages[{index}]"
        object_fields(message, {"id", "sender", "date", "text"}, at)
        integer(message["id"], at + ".id")
        require(0 < message["id"] <= 2**31 - 1, at, "message ID must be positive int32")
        require(message["id"] not in ids, at, "duplicate message ID")
        ids.add(message["id"])
        text(message["sender"], at + ".sender")
        text(message["text"], at + ".text")
        date = timestamp(message["date"], at + ".date")
        order = (date, message["id"])
        require(previous is None or order > previous, at, "messages must be chronological, then ID ordered")
        require(date <= snapshot, at, "message lies after the fixed snapshot")
        previous = order

    expected = data["expected"]
    object_fields(expected, {"facts", "tasks", "no_final_conclusion", "forbidden_claims"}, where + ".expected")
    require(type(expected["no_final_conclusion"]) is bool, where, "no_final_conclusion must be boolean")
    for name in ("facts", "tasks", "forbidden_claims"):
        require(isinstance(expected[name], list), where, f"{name} must be a list")
    require(bool(expected["facts"]) and bool(expected["forbidden_claims"]), where,
            "include expected facts and explicitly forbidden invented claims")
    item_ids = set()
    for category, items in (("facts", expected["facts"]), ("tasks", expected["tasks"]),
                            ("forbidden_claims", expected["forbidden_claims"])):
        for item in items:
            at = where + ".expected." + category
            require(isinstance(item, dict), at, "expected object")
            item_id = item.get("id")
            text(item_id, at + ".id")
            require(item_id not in item_ids, at, "duplicate expectation ID")
            item_ids.add(item_id)
            if category == "facts":
                object_fields(item, {"id", "statement", "source_ids", "state"}, at)
                text(item["statement"], at + ".statement")
                require(isinstance(item["state"], str) and item["state"] in STATES,
                        at, "unknown factual certainty/state")
                sources(item["source_ids"], ids, at + ".source_ids")
            elif category == "forbidden_claims":
                object_fields(item, {"id", "statement", "reason", "related_source_ids"}, at)
                text(item["statement"], at + ".statement")
                text(item["reason"], at + ".reason")
                sources(item["related_source_ids"], ids, at + ".related_source_ids")
            else:
                object_fields(item, {"id", "description", "source_ids", "owner", "owner_source_ids",
                                     "deadline", "deadline_source_ids"}, at)
                text(item["description"], at + ".description")
                sources(item["source_ids"], ids, at + ".source_ids")
                for field in ("owner", "deadline"):
                    refs = item[field + "_source_ids"]
                    sources(refs, ids, at + "." + field + "_source_ids", allow_empty=True)
                    require(set(refs) <= set(item["source_ids"]), at, f"{field} sources must also support the task")
                    if item[field] is None:
                        require(not refs, at, f"unspecified {field} must have no asserted evidence")
                    else:
                        text(item[field], at + "." + field)
                        require(bool(refs), at, f"an assigned {field} requires explicit source IDs")
    require(isinstance(data["review_notes"], list) and bool(data["review_notes"]), where, "review_notes required")
    for note in data["review_notes"]:
        text(note, where + ".review_notes")
    return data


def load_corpus(directory):
    paths = sorted(directory.glob("*.json"))
    require(len(paths) >= 20, str(directory), "at least 20 fixture cases are required")
    cases = []
    seen_ids, tags = set(), set()
    for path in paths:
        data = validate_fixture(json.loads(path.read_text(encoding="utf-8")), path.name)
        require(data["case_id"] not in seen_ids, str(path), "duplicate case_id")
        seen_ids.add(data["case_id"])
        tags.update(data["tags"])
        cases.append((path, data))
    require(REQUIRED_TAGS <= tags, str(directory), f"missing coverage tags: {sorted(REQUIRED_TAGS - tags)}")
    return cases


def review_template(cases):
    records = []
    for path, data in cases:
        checks = lambda items: [{"expectation_id": item["id"], "status": "not_evaluated", "notes": None}
                               for item in items]
        records.append({
            "case_id": data["case_id"],
            "fixture_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "message_count": len(data["messages"]),
            "source_characters": sum(len(message["text"]) for message in data["messages"]),
            "reference_map": [{"ref": f"[m{i + 1}]", "message_id": message["id"]}
                              for i, message in enumerate(data["messages"])],
            "execution_status": "not_run",
            "output_text": None,
            "elapsed_ms": None,
            "review": {"reviewer": None, "facts": checks(data["expected"]["facts"]),
                       "tasks": checks(data["expected"]["tasks"]),
                       "forbidden_claims_absent": checks(data["expected"]["forbidden_claims"]),
                       "citation_support": "not_evaluated", "uncertainty_preserved": "not_evaluated",
                       "overall_manual_verdict": "not_evaluated", "notes": None},
        })
    return {
        "schema_version": 1,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "template_only": True,
        "model_was_called": False,
        "automated_quality_verdict": "not_evaluated",
        "run": {"device": None, "android_version": None, "apk_commit": None,
                "mnn_version": None, "model_id": None, "model_quantization": None,
                "backend": None, "context_length": None, "prompt_commit": None,
                "run_date": None, "operator": None},
        "cases": records,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parent / "cases")
    parser.add_argument("--template", type=Path,
                        help="write a NEW evaluation record; never runs a model or overwrites a result")
    args = parser.parse_args()
    try:
        cases = load_corpus(args.fixtures)
        if args.template is not None:
            # Exclusive creation protects existing device/model measurements from replacement.
            with args.template.open("x", encoding="utf-8") as output:
                json.dump(review_template(cases), output, ensure_ascii=False, indent=2)
                output.write("\n")
            print(f"Wrote unrun review template: {args.template}")
        print(f"Validated {len(cases)} synthetic fixtures: structure and source-ID integrity only.")
        print("Model calls: 0. Model quality and device behavior: NOT EVALUATED.")
    except (InvalidFixture, OSError, json.JSONDecodeError) as error:
        print(f"Fixture validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
