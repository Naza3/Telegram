#!/usr/bin/env python3
"""Restore the pinned Android signer from Actions secrets, without logging secrets.

Only the four ANDROID_* environment variables below contain key material. No
replacement key is generated and no cache or network location is consulted.
"""

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile


EXPECTED_SHA256 = "c8715ba0c50510b9c1cc4d52b0fd2b0aed82dd55675f89c4f48d68978b7df313"
SECRET_NAMES = (
    "ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
)
MAX_KEYSTORE_BYTES = 64 * 1024
SUCCESS_MESSAGE = "Android signing key restored and verified."
ERRORS = {
    "arguments": "Invalid signing preparation arguments.",
    "missing": "All four Android signing secrets must be configured.",
    "base64": "ANDROID_KEYSTORE_BASE64 is not a valid bounded base64 keystore.",
    "output": "Signing output must be a regular file in a directory without symlinks.",
    "tools": "A JDK with keytool and jarsigner is required to validate signing secrets.",
    "store": "The signing keystore, store password or alias could not be validated.",
    "entry": "The signing alias must contain a private key entry.",
    "certificate": "The signing certificate does not match the expected certificate.",
    "key": "The signing private key password could not be validated.",
    "prepare": "Android signing preparation failed; no replacement key was generated.",
}


class SigningError(Exception):
    def __init__(self, reason):
        super().__init__(ERRORS[reason])


class QuietParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes bad argument values, which may be sensitive.
        raise SigningError("arguments")


def decode_keystore(environ):
    if any(not environ.get(name) for name in SECRET_NAMES):
        raise SigningError("missing")
    encoded = environ["ANDROID_KEYSTORE_BASE64"]
    if len(encoded) > MAX_KEYSTORE_BYTES * 2:
        raise SigningError("base64")
    try:
        # Accept the line wrapping produced by common base64 command-line tools.
        compact = re.sub(r"[ \t\r\n]", "", encoded)
        decoded = base64.b64decode(compact, validate=True)
    except (ValueError, binascii.Error):
        raise SigningError("base64") from None
    if not 0 < len(decoded) <= MAX_KEYSTORE_BYTES:
        raise SigningError("base64")
    return decoded


def java_tool(name, environ):
    java_home = environ.get("JAVA_HOME")
    candidate = str(Path(java_home) / "bin" / name) if java_home else shutil.which(name)
    if not candidate or not os.path.isfile(candidate) or not os.access(candidate, os.X_OK):
        raise SigningError("tools")
    return candidate


def checked_run(command, environ, reason):
    try:
        result = subprocess.run(
            command, env=environ, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=45, check=False,
        )
    except (OSError, ValueError, subprocess.SubprocessError):
        raise SigningError(reason) from None
    if result.returncode != 0:
        raise SigningError(reason)
    return result.stdout


def validate_keystore(keystore, directory, expected, environ):
    keytool = java_tool("keytool", environ)
    jarsigner = java_tool("jarsigner", environ)
    child_env = dict(environ)
    # Do not pass the encoded private key to child processes or allow Java's
    # environment options to print diagnostics or inject a different provider.
    for name in ("ANDROID_KEYSTORE_BASE64", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        child_env.pop(name, None)
    common = [
        "-J-Duser.language=en", "-J-Duser.country=US",
        "-keystore", str(keystore),
        "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        "-alias", environ["ANDROID_KEY_ALIAS"],
    ]
    listing = checked_run([keytool, "-list", "-v", *common], child_env, "store")
    if not re.search(rb"^Entry type: PrivateKeyEntry\s*$", listing, re.MULTILINE):
        raise SigningError("entry")
    certificate = checked_run([keytool, "-exportcert", *common], child_env, "store")
    if not certificate or hashlib.sha256(certificate).hexdigest() != expected:
        raise SigningError("certificate")

    # A successful store-password check does not prove the private-key password
    # works (particularly for JKS). Sign a synthetic JAR before starting Gradle.
    probe = directory / "signing-probe.jar"
    descriptor = os.open(probe, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        with zipfile.ZipFile(stream, "w") as jar:
            jar.writestr("signing-probe.txt", "Android signing validation\n")
    checked_run([
        jarsigner, "-J-Duser.language=en", "-J-Duser.country=US",
        "-keystore", str(keystore),
        "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        "-keypass:env", "ANDROID_KEY_PASSWORD",
        "-sigfile", "PROBE", str(probe), environ["ANDROID_KEY_ALIAS"],
    ], child_env, "key")
    # Guard against a tool returning success without actually writing a signature.
    with zipfile.ZipFile(probe) as signed:
        if "META-INF/PROBE.SF" not in signed.namelist():
            raise SigningError("key")


def check_output(output):
    """Reject both target symlinks and symlinks in its directory components."""
    current = Path(output.anchor)
    for part in output.parts[1:-1]:
        current /= part
        try:
            current.mkdir(mode=0o700)
        except FileExistsError:
            pass
        mode = current.lstat().st_mode
        if not stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
            raise SigningError("output")
    try:
        mode = output.lstat().st_mode
    except FileNotFoundError:
        return
    if not stat.S_ISREG(mode) or stat.S_ISLNK(mode):
        raise SigningError("output")


def prepare(output, expected=EXPECTED_SHA256, environ=None):
    environ = os.environ if environ is None else environ
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise SigningError("arguments")
    contents = decode_keystore(environ)
    # abspath normalizes '.' and '..' without following symlinks.
    output = Path(os.path.abspath(output))
    try:
        check_output(output)
        with tempfile.TemporaryDirectory(prefix=".android-signing-", dir=output.parent) as temporary:
            directory = Path(temporary)
            keystore = directory / "keystore"
            descriptor = os.open(keystore, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(contents)
                stream.flush()
                os.fsync(stream.fileno())
            validate_keystore(keystore, directory, expected.lower(), environ)
            check_output(output)
            os.replace(keystore, output)
    except SigningError:
        raise
    except (OSError, ValueError, zipfile.BadZipFile):
        raise SigningError("prepare") from None


def main(argv=None):
    try:
        parser = QuietParser(description=__doc__)
        parser.add_argument("--output", required=True, help="Local keystore destination")
        parser.add_argument("--expected-sha256", default=EXPECTED_SHA256,
                            help="Expected certificate SHA-256 (default: the existing Telegram signer)")
        args = parser.parse_args(argv)
        prepare(args.output, args.expected_sha256)
    except SigningError as error:
        print(error, file=sys.stderr)
        return 2
    print(SUCCESS_MESSAGE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
