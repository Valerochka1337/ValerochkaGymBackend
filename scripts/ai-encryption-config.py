#!/usr/bin/env python3
"""Deliver the stable AI encryption key without logging secrets or sourcing dotenv."""
import base64
import binascii
import json
import os
from pathlib import Path
import re
import sys
import tempfile

KEY = "AI_SETTINGS_ENCRYPTION_KEY"


class ConfigError(ValueError):
    pass


def validate(config):
    if not isinstance(config, dict) or set(config) != {KEY}:
        raise ConfigError("Missing AI_SETTINGS_ENCRYPTION_KEY production secret")
    value = config[KEY]
    if not isinstance(value, str):
        raise ConfigError("AI_SETTINGS_ENCRYPTION_KEY must be base64 of 32 random bytes")
    try:
        decoded = base64.b64decode(value, validate=True)
        if len(decoded) != 32 or base64.b64encode(decoded).decode() != value:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise ConfigError("AI_SETTINGS_ENCRYPTION_KEY must be base64 of 32 random bytes") from None
    return config


def from_environment(env):
    return validate({KEY: env.get(KEY, "")})


def apply_config(config, destination):
    value = validate(config)[KEY]
    destination = Path(destination)
    lines = destination.read_text().splitlines(keepends=True)
    retained = []
    for line in lines:
        match = re.match(r"\s*(?:export\s+)?AI_SETTINGS_ENCRYPTION_KEY\s*=(.*)", line)
        if match is None:
            retained.append(line)
            continue
        # Only the canonical Base64 alphabet is accepted; no shell expansion/evaluation.
        old = match.group(1).strip()
        parsed = re.fullmatch(r'''(?:"([A-Za-z0-9+/=]*)"|'([A-Za-z0-9+/=]*)'|([A-Za-z0-9+/=]*))(?:\s+#.*)?''', old)
        if parsed is None:
            raise ConfigError("Cannot safely read existing AI encryption key; server settings were not changed")
        previous = next(part for part in parsed.groups() if part is not None)
        if previous and previous != value:
            raise ConfigError("AI encryption key differs from the server key; automatic key rotation is refused")
    content = "".join(retained)
    if content and not content.endswith("\n"):
        content += "\n"
    content += f'{KEY}="{value}"\n'
    fd, temporary = tempfile.mkstemp(prefix=".ai-key-env-", dir=destination.parent)
    try:
        with os.fdopen(fd, "w") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    try:
        if sys.argv[1:] == ["export"]:
            print(json.dumps(from_environment(os.environ)))
        elif len(sys.argv) == 4 and sys.argv[1] == "apply":
            apply_config(json.loads(Path(sys.argv[2]).read_text()), sys.argv[3])
        else:
            raise ConfigError("Usage: ai-encryption-config.py export | apply PAYLOAD ENV_FILE")
    except ConfigError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (ValueError, OSError):
        print("Invalid AI encryption configuration; check production secret and server settings", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
