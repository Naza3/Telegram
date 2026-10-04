"""Exercise signing-secret restoration with synthetic keys, never real credentials."""

import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


HELPER_PATH = Path(__file__).resolve().parents[2] / "tools" / "prepare-android-signing.py"
SPEC = importlib.util.spec_from_file_location("android_signing_preparation", HELPER_PATH)
helper = importlib.util.module_from_spec(SPEC)
previous_bytecode_setting = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True
    SPEC.loader.exec_module(helper)
finally:
    sys.dont_write_bytecode = previous_bytecode_setting


class AndroidSigningPreparationTest(unittest.TestCase):
    STORE_PASSWORD = "synthetic-store-password"
    KEY_PASSWORD = "synthetic-key-password"
    ALIAS = "synthetic-key-alias"

    @classmethod
    def setUpClass(cls):
        cls.fixture_directory = tempfile.TemporaryDirectory(prefix="synthetic-signing-tests-")
        cls.addClassCleanup(cls.fixture_directory.cleanup)
        cls.fixture = Path(cls.fixture_directory.name)
        java_home = os.environ.get("JAVA_HOME")
        if not java_home:
            keytool = shutil.which("keytool")
            if keytool:
                java_home = str(Path(keytool).resolve().parent.parent)
        if not java_home or not (Path(java_home) / "bin" / "jarsigner").is_file():
            raise RuntimeError("These signing tests require a JDK containing keytool and jarsigner.")
        cls.base_env = {
            "PATH": str(Path(java_home) / "bin") + os.pathsep + os.defpath,
            "JAVA_HOME": java_home,
            "ANDROID_KEYSTORE_PASSWORD": cls.STORE_PASSWORD,
            "ANDROID_KEY_PASSWORD": cls.KEY_PASSWORD,
            "ANDROID_KEY_ALIAS": cls.ALIAS,
        }
        cls.keytool = str(Path(java_home) / "bin" / "keytool")
        cls.jks = cls.fixture / "synthetic.jks"
        cls.run_keytool([
            "-genkeypair", "-noprompt", "-storetype", "JKS", "-keystore", str(cls.jks),
            "-alias", cls.ALIAS, "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
            "-keypass:env", "ANDROID_KEY_PASSWORD", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Synthetic signing test", "-validity", "2",
        ])
        cls.jks_bytes = cls.jks.read_bytes()
        certificate = cls.run_keytool([
            "-exportcert", "-keystore", str(cls.jks), "-alias", cls.ALIAS,
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        ])
        cls.expected = hashlib.sha256(certificate).hexdigest()
        cls.cert_file = cls.fixture / "certificate.der"
        cls.cert_file.write_bytes(certificate)
        cls.certificate_only = cls.fixture / "certificate-only.p12"
        cls.run_keytool([
            "-importcert", "-noprompt", "-storetype", "PKCS12", "-keystore", str(cls.certificate_only),
            "-alias", cls.ALIAS, "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
            "-file", str(cls.cert_file),
        ])
        cls.pkcs12 = cls.fixture / "synthetic.p12"
        cls.run_keytool([
            "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(cls.pkcs12),
            "-alias", cls.ALIAS, "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
            "-keypass:env", "ANDROID_KEYSTORE_PASSWORD", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Synthetic PKCS12 signing test", "-validity", "2",
        ])
        cls.pkcs12_expected = hashlib.sha256(cls.run_keytool([
            "-exportcert", "-keystore", str(cls.pkcs12), "-alias", cls.ALIAS,
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        ])).hexdigest()

    @classmethod
    def run_keytool(cls, args, environ=None):
        result = subprocess.run(
            [cls.keytool, *args], env=cls.base_env if environ is None else environ, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False,
        )
        if result.returncode:
            raise RuntimeError("Could not create synthetic signing fixture.")
        return result.stdout

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="output-", dir=self.fixture)
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "restored.keystore"
        self.environ = {
            **self.base_env,
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(self.jks_bytes).decode("ascii"),
        }

    def run_cli(self, environ=None, expected=None, arguments=None):
        command = [sys.executable, str(HELPER_PATH), "--output", str(self.output)]
        if expected is not None:
            command += ["--expected-sha256", expected]
        elif arguments is None:
            command += ["--expected-sha256", self.expected]
        command += arguments or []
        return subprocess.run(
            command, env=self.environ if environ is None else environ,
            capture_output=True, text=True, timeout=120, check=False,
        )

    def assert_rejected(self, result, reason, previous=None):
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual(helper.ERRORS[reason] + "\n", result.stderr)
        if previous is None:
            self.assertFalse(self.output.exists())
        else:
            self.assertEqual(previous, self.output.read_bytes())
        self.assertEqual([], list(self.output.parent.glob(".android-signing-*")))
        for name in helper.SECRET_NAMES:
            self.assertNotIn(self.environ[name], result.stdout + result.stderr)

    def test_valid_private_key_replaces_output_privately_and_uses_env_passwords(self):
        self.output.write_bytes(b"old key file")
        self.output.chmod(0o644)
        self.environ["JAVA_TOOL_OPTIONS"] = "SYNTHETIC_INJECTED_JAVA_OPTION"
        self.environ["_JAVA_OPTIONS"] = "SYNTHETIC_INJECTED_JAVA_OPTION"
        self.environ["JDK_JAVA_OPTIONS"] = "SYNTHETIC_INJECTED_JAVA_OPTION"
        real_run = subprocess.run
        with mock.patch.object(helper.subprocess, "run", side_effect=real_run) as run:
            helper.prepare(self.output, self.expected, self.environ)
        self.assertEqual(self.jks_bytes, self.output.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        self.assertEqual(3, run.call_count)
        for call in run.call_args_list:
            command = call.args[0]
            self.assertNotIn(self.STORE_PASSWORD, command)
            self.assertNotIn(self.KEY_PASSWORD, command)
            self.assertIn("-storepass:env", command)
            self.assertNotIn("ANDROID_KEYSTORE_BASE64", call.kwargs["env"])
            for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
                self.assertNotIn(name, call.kwargs["env"])
        self.assertIn("-keypass:env", run.call_args_list[-1].args[0])
        self.assertEqual([], list(self.output.parent.glob(".android-signing-*")))

    def test_pkcs12_and_wrapped_base64_are_supported(self):
        self.environ["ANDROID_KEYSTORE_BASE64"] = base64.encodebytes(self.pkcs12.read_bytes()).decode("ascii")
        self.environ["ANDROID_KEY_PASSWORD"] = self.STORE_PASSWORD
        result = self.run_cli(expected=self.pkcs12_expected.upper())
        self.assertEqual(0, result.returncode)
        self.assertEqual(helper.SUCCESS_MESSAGE + "\n", result.stdout)
        self.assertEqual("", result.stderr)
        self.assertEqual(self.pkcs12.read_bytes(), self.output.read_bytes())

    def test_missing_each_secret_stops_without_creating_key(self):
        for name in helper.SECRET_NAMES:
            with self.subTest(name=name):
                environ = dict(self.environ)
                del environ[name]
                self.assert_rejected(self.run_cli(environ), "missing")

    def test_invalid_empty_or_oversized_base64_is_rejected(self):
        for value in ("not-base64!", "\\x00", " ",
                      base64.b64encode(b"a" * (helper.MAX_KEYSTORE_BYTES + 1)).decode("ascii")):
            with self.subTest(length=len(value)):
                environ = {**self.environ, "ANDROID_KEYSTORE_BASE64": value}
                self.assert_rejected(self.run_cli(environ), "base64")
        # This input exceeds Linux's per-environment-variable execve limit, so
        # exercise the decoder directly rather than asking the OS to transport it.
        with self.assertRaisesRegex(helper.SigningError, helper.ERRORS["base64"]):
            helper.decode_keystore({
                **self.environ,
                "ANDROID_KEYSTORE_BASE64": "a" * (helper.MAX_KEYSTORE_BYTES * 2 + 1),
            })

    def test_corrupt_keystore_is_rejected_and_existing_file_is_unchanged(self):
        old = b"previous signer must remain unchanged"
        self.output.write_bytes(old)
        environ = {**self.environ, "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"invalid store").decode("ascii")}
        self.assert_rejected(self.run_cli(environ), "store_format", old)

    def test_wrong_store_password_does_not_log_external_diagnostics(self):
        sentinel = "SYNTHETIC_SECRET_DO_NOT_PRINT\nInjected error log"
        environ = {**self.environ, "ANDROID_KEYSTORE_PASSWORD": sentinel}
        result = self.run_cli(environ)
        self.assert_rejected(result, "store_password")
        self.assertNotIn(sentinel, result.stderr)
        self.assertNotIn("Injected error log", result.stderr)

    def test_unknown_alias_is_rejected_without_echoing_alias(self):
        sentinel = "SYNTHETIC_ALIAS_DO_NOT_PRINT"
        result = self.run_cli({**self.environ, "ANDROID_KEY_ALIAS": sentinel})
        self.assert_rejected(result, "alias")
        self.assertNotIn(sentinel, result.stderr)

    def test_wrong_pkcs12_password_is_classified_without_logging_diagnostics(self):
        sentinel = "SYNTHETIC_WRONG_PKCS12_PASSWORD"
        environ = {
            **self.environ,
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(self.pkcs12.read_bytes()).decode("ascii"),
            "ANDROID_KEYSTORE_PASSWORD": sentinel,
        }
        result = self.run_cli(environ, expected=self.pkcs12_expected)
        self.assert_rejected(result, "store_password")
        self.assertNotIn(sentinel, result.stderr)

    def test_whitespace_hint_follows_password_or_alias_failure_without_trimming(self):
        for name, value, reason in (
            ("ANDROID_KEYSTORE_PASSWORD", self.STORE_PASSWORD + "\n", "store_password_whitespace"),
            ("ANDROID_KEYSTORE_PASSWORD", " " + self.STORE_PASSWORD, "store_password_whitespace"),
            ("ANDROID_KEY_ALIAS", " " + self.ALIAS, "alias_whitespace"),
            ("ANDROID_KEY_ALIAS", self.ALIAS + "\n", "alias_whitespace"),
        ):
            with self.subTest(name=name, reason=reason):
                result = self.run_cli({**self.environ, name: value})
                self.assert_rejected(result, reason)
                self.assertNotIn(value, result.stderr)

    def test_valid_whitespace_password_and_alias_are_preserved(self):
        environ = {
            **self.environ,
            "ANDROID_KEYSTORE_PASSWORD": " " + self.STORE_PASSWORD + " ",
            "ANDROID_KEY_PASSWORD": " " + self.KEY_PASSWORD + " ",
            "ANDROID_KEY_ALIAS": " " + self.ALIAS + " ",
        }
        spaced_store = self.output.parent / "synthetic-spaced.jks"
        self.run_keytool([
            "-genkeypair", "-noprompt", "-storetype", "JKS", "-keystore", str(spaced_store),
            "-alias", environ["ANDROID_KEY_ALIAS"], "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
            "-keypass:env", "ANDROID_KEY_PASSWORD", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=Synthetic whitespace test", "-validity", "2",
        ], environ)
        certificate = self.run_keytool([
            "-exportcert", "-keystore", str(spaced_store), "-alias", environ["ANDROID_KEY_ALIAS"],
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        ], environ)
        environ["ANDROID_KEYSTORE_BASE64"] = base64.b64encode(spaced_store.read_bytes()).decode("ascii")
        result = self.run_cli(environ, expected=hashlib.sha256(certificate).hexdigest())
        self.assertEqual(0, result.returncode)
        self.assertEqual(helper.SUCCESS_MESSAGE + "\n", result.stdout)
        self.assertEqual("", result.stderr)
        self.assertEqual(spaced_store.read_bytes(), self.output.read_bytes())

    def test_alias_cannot_inject_password_diagnostics(self):
        sentinel = "SYNTHETIC_ALIAS\nkeytool error: java.io.IOException: keystore password was incorrect"
        result = self.run_cli({**self.environ, "ANDROID_KEY_ALIAS": sentinel})
        self.assert_rejected(result, "alias")
        self.assertNotIn("SYNTHETIC_ALIAS", result.stderr)

    def test_unknown_tool_diagnostics_remain_generic_and_private(self):
        sentinel = b"SYNTHETIC_UNKNOWN_TOOL_DIAGNOSTIC_DO_NOT_PRINT"
        completed = subprocess.CompletedProcess([], 1, sentinel, sentinel)
        with mock.patch.object(helper.subprocess, "run", return_value=completed):
            with self.assertRaises(helper.SigningError) as caught:
                helper.prepare(self.output, self.expected, self.environ)
        self.assertEqual(helper.ERRORS["store"], str(caught.exception))
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob(".android-signing-*")))

    def test_wrong_private_key_password_is_rejected_before_install(self):
        result = self.run_cli({**self.environ, "ANDROID_KEY_PASSWORD": "SYNTHETIC_WRONG_KEY_PASSWORD"})
        self.assert_rejected(result, "key")

    def test_certificate_only_store_is_not_accepted_as_signing_key(self):
        environ = {**self.environ, "ANDROID_KEYSTORE_BASE64": base64.b64encode(self.certificate_only.read_bytes()).decode("ascii")}
        self.assert_rejected(self.run_cli(environ), "entry")

    def test_wrong_certificate_and_default_certificate_pin_are_rejected(self):
        self.assert_rejected(self.run_cli(expected="0" * 64), "certificate")
        # Passing an explicit empty argument list retains the real default pin.
        self.assert_rejected(self.run_cli(arguments=[]), "certificate")

    def test_missing_jdk_fails_without_installing_key(self):
        environ = {**self.environ, "JAVA_HOME": str(self.fixture / "no-jdk")}
        self.assert_rejected(self.run_cli(environ), "tools")

    def test_target_symlink_is_rejected_without_modifying_its_target(self):
        target = self.output.parent / "target"
        target.write_bytes(b"unchanged")
        self.output.symlink_to(target)
        result = self.run_cli()
        self.assertEqual(2, result.returncode)
        self.assertEqual(helper.ERRORS["output"] + "\n", result.stderr)
        self.assertTrue(self.output.is_symlink())
        self.assertEqual(b"unchanged", target.read_bytes())
        self.assertEqual([], list(self.output.parent.glob(".android-signing-*")))

    def test_parent_symlink_is_rejected(self):
        actual = self.output.parent / "actual"
        actual.mkdir()
        linked = self.output.parent / "linked"
        linked.symlink_to(actual, target_is_directory=True)
        self.output = linked / "keystore"
        self.assert_rejected(self.run_cli(), "output")
        self.assertEqual([], list(actual.iterdir()))

    def test_invalid_arguments_never_echo_supplied_values(self):
        sentinel = "SYNTHETIC_INVALID_ARGUMENT_DO_NOT_PRINT"
        self.assert_rejected(self.run_cli(arguments=["--unknown", sentinel]), "arguments")
        self.assert_rejected(self.run_cli(expected=sentinel), "arguments")


if __name__ == "__main__":
    unittest.main()
