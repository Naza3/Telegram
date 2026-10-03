"""Synthetic credential tests; these tests never read actual build credentials."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest


VALIDATOR_PATH = Path(__file__).resolve().parents[2] / "tools" / "check-telegram-api.py"
SPEC = importlib.util.spec_from_file_location("telegram_api_validator", VALIDATOR_PATH)
validator = importlib.util.module_from_spec(SPEC)
previous_bytecode_setting = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True
    SPEC.loader.exec_module(validator)
finally:
    sys.dont_write_bytecode = previous_bytecode_setting

# This shared table may also be used to exercise the independent Gradle guard.
VALID_ID = "12345678"
VALID_HASH = "0123456789abcdef0123456789abcdef"
VALID_CASES = (
    ("minimum", "1", VALID_HASH),
    ("maximum", "2147483647", VALID_HASH),
    ("uppercase", VALID_ID, "ABCDEF0123456789ABCDEF0123456789"),
    ("mixedcase", VALID_ID, "aBcDeF0123456789AbCdEf0123456789"),
)
INVALID_IDS = (
    "", "0", "-1", "+1", "01", "0000000001", "2147483648", "9999999999",
    "10000000000", "9" * 5000, "1.0", "1e3", " 1", "1 ", "1\n", "1\r\n",
    "1\t", "1\x00", "١٢٣", "１２３", "1;echo TEST", "$(echo TEST)", "`echo TEST`",
)
INVALID_HASHES = (
    "", "a" * 31, "a" * 33, "g" * 32, "ａ" * 32, "١" * 32,
    " " + VALID_HASH, VALID_HASH + " ", VALID_HASH + "\n", VALID_HASH + "\r\n",
    VALID_HASH[:15] + "\t" + VALID_HASH[16:], VALID_HASH[:31] + "\x00",
    '";echo SYNTHETIC_SECRET_VALUE;#', "$(echo SYNTHETIC_SECRET_VALUE)",
)


class TelegramApiValidationTest(unittest.TestCase):
    def test_valid_boundaries_and_hex_case(self):
        for name, api_id, api_hash in VALID_CASES:
            with self.subTest(name=name):
                self.assertEqual((), validator.validation_errors({
                    "TELEGRAM_API_ID": api_id, "TELEGRAM_API_HASH": api_hash,
                }))

    def test_missing_and_partial_configuration(self):
        self.assertEqual(
            (validator.ID_ERROR, validator.HASH_ERROR), validator.validation_errors({}))
        self.assertEqual((validator.HASH_ERROR,), validator.validation_errors({
            "TELEGRAM_API_ID": VALID_ID,
        }))
        self.assertEqual((validator.ID_ERROR,), validator.validation_errors({
            "TELEGRAM_API_HASH": VALID_HASH,
        }))

    def test_invalid_id_never_converts_unbounded_or_malformed_input(self):
        for index, value in enumerate(INVALID_IDS):
            with self.subTest(case=index):
                self.assertEqual((validator.ID_ERROR,), validator.validation_errors({
                    "TELEGRAM_API_ID": value, "TELEGRAM_API_HASH": VALID_HASH,
                }))

    def test_invalid_hash_rejects_whitespace_unicode_and_shell_syntax(self):
        for index, value in enumerate(INVALID_HASHES):
            with self.subTest(case=index):
                self.assertEqual((validator.HASH_ERROR,), validator.validation_errors({
                    "TELEGRAM_API_ID": VALID_ID, "TELEGRAM_API_HASH": value,
                }))

    def run_cli(self, environ):
        # Use only synthetic variables: no inherited credentials, PATH, or PYTHONPATH.
        return subprocess.run(
            [sys.executable, str(VALIDATOR_PATH)], env=environ,
            capture_output=True, text=True, timeout=10, check=False,
        )

    def test_cli_success_prints_no_credentials(self):
        result = self.run_cli({"TELEGRAM_API_ID": VALID_ID, "TELEGRAM_API_HASH": VALID_HASH})
        self.assertEqual(0, result.returncode)
        self.assertEqual(validator.SUCCESS_MESSAGE + "\n", result.stdout)
        self.assertEqual("", result.stderr)
        self.assertNotIn(VALID_ID, result.stdout + result.stderr)
        self.assertNotIn(VALID_HASH, result.stdout + result.stderr)

    def test_cli_failure_prints_only_constant_errors(self):
        sentinel = "SYNTHETIC_SECRET_VALUE"
        result = self.run_cli({
            "TELEGRAM_API_ID": sentinel + "\nInjected log message",
            "TELEGRAM_API_HASH": "$(echo " + sentinel + ")",
        })
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual(validator.ID_ERROR + "\n" + validator.HASH_ERROR + "\n", result.stderr)
        self.assertNotIn(sentinel, result.stdout + result.stderr)
        self.assertNotIn("Injected log message", result.stdout + result.stderr)

    def test_cli_missing_credentials_fails_closed(self):
        result = self.run_cli({})
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual(validator.ID_ERROR + "\n" + validator.HASH_ERROR + "\n", result.stderr)


if __name__ == "__main__":
    unittest.main()
