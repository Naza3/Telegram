#!/usr/bin/env python3
"""Validate build-time Telegram API environment variables without displaying them."""

import os
import re
import sys
from typing import Mapping, Tuple


API_ID_NAME = "TELEGRAM_API_ID"
API_HASH_NAME = "TELEGRAM_API_HASH"
SUCCESS_MESSAGE = "Telegram API build configuration is valid."
ID_ERROR = (
    "TELEGRAM_API_ID must be an ASCII decimal integer from 1 to 2147483647 "
    "without leading zeroes or whitespace."
)
HASH_ERROR = (
    "TELEGRAM_API_HASH must contain exactly 32 ASCII hexadecimal characters "
    "without whitespace."
)


def validation_errors(environ: Mapping[str, str]) -> Tuple[str, ...]:
    """Return constant error messages; never include supplied environment values."""
    errors = []
    api_id = environ.get(API_ID_NAME, "")
    # Bound the digit count before conversion, and reject Java octal notation.
    if not re.fullmatch(r"[1-9][0-9]{0,9}", api_id) or int(api_id) > 2147483647:
        errors.append(ID_ERROR)
    if not re.fullmatch(r"[0-9a-fA-F]{32}", environ.get(API_HASH_NAME, "")):
        errors.append(HASH_ERROR)
    return tuple(errors)


def main() -> int:
    errors = validation_errors(os.environ)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 2
    print(SUCCESS_MESSAGE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
