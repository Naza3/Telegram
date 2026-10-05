"""Synthetic tag/version tests. No network, signing keys, or build credentials are used."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).resolve().parents[2] / "tools" / "check-release-tag.py"
SPEC = importlib.util.spec_from_file_location("release_tag", TOOL)
tagger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tagger)
BASELINE = "APP_VERSION_CODE=7113\nAPP_VERSION_NAME=12.10.6\n"


class ReleaseTagTest(unittest.TestCase):
    def test_expected_version_and_same_tag_retry(self):
        tag = "mnn-v12.10.6-7113"
        result = tagger.validate_release(tag, BASELINE, ["unrelated-tag", "mnn-v12.10.5-7112", tag])
        self.assertEqual(result, {
            "MNN_RELEASE_TAG": tag,
            "MNN_RELEASE_VERSION_NAME": "12.10.6-mnn.7113",
            "MNN_RELEASE_VERSION_CODE": "7113",
            "MNN_RELEASE_APK_VERSION_CODE": "71139",
        })

    def test_android_afat_boundary(self):
        props = f"APP_VERSION_CODE={tagger.MAX_BASE_CODE}\nAPP_VERSION_NAME=1.0.0\n"
        result = tagger.validate_release(f"mnn-v1.0.0-{tagger.MAX_BASE_CODE}", props)
        self.assertEqual(result["MNN_RELEASE_APK_VERSION_CODE"], "2099999999")
        for code in ("210000000", "214748364", "999999999", "1000000000"):
            with self.subTest(code=code), self.assertRaises(tagger.ReleaseTagError):
                tagger.validate_release("mnn-v1.0.0-" + code, props)

    def test_invalid_formats_and_shell_payloads(self):
        invalid = [
            "", "mnn-v1.2-7113", "mnn-v1.2.3.4-7113", "mnn-v01.2.3-7113",
            "mnn-v1.02.3-7113", "mnn-v1.2.03-7113", "mnn-v1.2.3-07113", "mnn-v1.2.3-0",
            "mnn-v1.2.3--1", "mnn-v1.2.3-+7113", "mnn-v1.2.3-7113-alpha", "mnn-v1.2.3-7e3",
            "mnn-v1.2.3-７１１３", "mnn-v١.2.3-7113", " mnn-v1.2.3-7113", "mnn-v1.2.3-7113 ",
            "mnn-v1.2.3-7113\n", "mnn-v1.2.3-7113\r\n", "mnn-v1.2.3-7113\x00",
            "mnn-v1.2.3-7113;echo INJECTED", "$(touch should-not-exist)", "`touch should-not-exist`",
            "mnn-v1.2.3-7113\nMNN_RELEASE_VERSION_CODE=999999", "mnn-v1.2.3-" + "9" * 5000,
            "refs/tags/mnn-v1.2.3-7113", "mnn-v1000000.0.0-7113",
        ]
        for tag in invalid:
            with self.subTest(tag=tag[:50]):
                with self.assertRaises(tagger.ReleaseTagError):
                    tagger.validate_release(tag, BASELINE)

    def test_monotonic_code_and_no_reuse(self):
        for tag, previous in [
            ("mnn-v12.10.6-7112", []), ("mnn-v12.10.6-7114", []),
            ("mnn-v12.10.7-7113", []),
            ("mnn-v12.10.6-7113", ["mnn-v12.10.7-7114"]),
            ("mnn-v12.10.6-7113", ["mnn-v12.10.5-7113"]),
            ("mnn-v12.10.6-7113", ["mnn-vmalformed"]),
        ]:
            with self.subTest(tag=tag, previous=previous), self.assertRaises(tagger.ReleaseTagError):
                tagger.validate_release(tag, BASELINE, previous)

    def test_baseline_is_unique_and_strict(self):
        for properties in ["", "APP_VERSION_CODE=0", "APP_VERSION_CODE=07112", "APP_VERSION_CODE=-1",
                           "APP_VERSION_CODE=7112\nAPP_VERSION_CODE=7111", "APP_VERSION_CODE=7112junk",
                           "APP_VERSION_CODE=210000000", "APP_VERSION_CODE=７１１２"]:
            with self.subTest(properties=properties), self.assertRaises(tagger.ReleaseTagError):
                tagger.validate_release("mnn-v12.10.6-7113", properties)
        self.assertEqual(tagger.baseline_code("  APP_VERSION_CODE = 7112 \t\n"), 7112)
        for version in ("", "12.10", "012.10.6", "12.10.6junk", "12.10.6\nAPP_VERSION_NAME=1.0.0"):
            with self.subTest(version=version), self.assertRaises(tagger.ReleaseTagError):
                tagger.validate_release("mnn-v12.10.6-7113", "APP_VERSION_CODE=7113\nAPP_VERSION_NAME=" + version)

    def test_cli_safe_outputs_and_invalid_input_writes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            properties, tags = root / "gradle.properties", root / "tags"
            properties.write_text(BASELINE)
            tags.write_text("")
            env_out, action_out, metadata = root / "env", root / "output", root / "version.json"
            command = [sys.executable, "-B", str(TOOL), "--properties", str(properties), "--tags-file", str(tags),
                       "--github-env", str(env_out), "--github-output", str(action_out), "--json-output", str(metadata)]
            env = {**os.environ, "MNN_RELEASE_TAG_INPUT": "mnn-v12.10.6-7113"}
            result = subprocess.run(command, env=env, cwd=root, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(env_out.read_text(), action_out.read_text())
            self.assertEqual(json.loads(metadata.read_text())["MNN_RELEASE_APK_VERSION_CODE"], "71139")
            previous = env_out.read_bytes()
            env["MNN_RELEASE_TAG_INPUT"] = "$(touch should-not-exist)"
            result = subprocess.run(command, env=env, cwd=root, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn(env["MNN_RELEASE_TAG_INPUT"], result.stdout + result.stderr)
            self.assertEqual(env_out.read_bytes(), previous)
            self.assertFalse((root / "should-not-exist").exists())


if __name__ == "__main__":
    unittest.main()
