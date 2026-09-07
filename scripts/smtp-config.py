#!/usr/bin/env python3
"""Transfer only SMTP settings; never source dotenv or print credentials in diagnostics."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile

KEYS = {"MAIL_ENABLED", "MAIL_FROM", "SMTP_HOST", "SMTP_PORT", "SMTP_USERNAME",
        "SMTP_PASSWORD", "SMTP_AUTH", "SMTP_TLS", "SMTP_SSL"}


class ConfigError(ValueError):
    """Only static messages and known configuration key names are safe to display."""


def validate(config):
    if not isinstance(config, dict) or set(config) - KEYS:
        raise ConfigError("Unexpected SMTP configuration keys")
    for key, value in config.items():
        if not isinstance(value, str) or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ConfigError(f"{key} must be a single-line value")
    if not config or config == {"MAIL_ENABLED": "false"}:
        return config
    if set(config) != KEYS or config["MAIL_ENABLED"] != "true":
        raise ConfigError("Incomplete SMTP configuration")
    for key in ("MAIL_FROM", "SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD"):
        if not config[key].strip():
            raise ConfigError(f"Missing {key} in production secrets")
    if not config["SMTP_PORT"].isascii() or not config["SMTP_PORT"].isdigit() or not 1 <= int(config["SMTP_PORT"]) <= 65535:
        raise ConfigError("SMTP_PORT must be between 1 and 65535")
    if config["SMTP_AUTH"] != "true" or (config["SMTP_TLS"], config["SMTP_SSL"]) not in {
        ("true", "false"), ("false", "true")
    }:
        raise ConfigError("SMTP requires authentication and exactly one TLS mode")
    return config


def from_environment(env):
    enabled = env.get("MAIL_ENABLED", "")
    if enabled == "":
        return {}  # Existing manually configured SMTP remains untouched until CD is enabled.
    if enabled == "false":
        return {"MAIL_ENABLED": "false"}
    if enabled != "true":
        raise ConfigError("MAIL_ENABLED must be true, false, or unset")
    security = env.get("SMTP_SECURITY") or "starttls"
    if security not in {"starttls", "ssl"}:
        raise ConfigError("SMTP_SECURITY must be starttls or ssl")
    return validate({
        "MAIL_ENABLED": "true",
        **{key: env.get(key, "") for key in ("MAIL_FROM", "SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD")},
        "SMTP_PORT": env.get("SMTP_PORT") or ("465" if security == "ssl" else "587"),
        "SMTP_AUTH": "true",
        "SMTP_TLS": str(security == "starttls").lower(),
        "SMTP_SSL": str(security == "ssl").lower(),
    })


def apply_config(config, destination):
    validate(config)
    if not config:
        return
    destination = Path(destination)
    # Existing server-generated values and unrelated settings are kept byte-for-byte.
    lines = destination.read_text().splitlines(keepends=True)
    retained = []
    for line in lines:
        match = re.match(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=", line)
        if match is None or match.group(1) not in config:
            retained.append(line)
    content = "".join(retained)
    if content and not content.endswith("\n"):
        content += "\n"
    for key, value in sorted(config.items()):
        # Compose expands $ in double quotes. $$ preserves literal dollars, while JSON
        # quoting handles quotes and backslashes. This file must not be sourced by a shell.
        content += f"{key}={json.dumps(value.replace('$', '$$'), ensure_ascii=False)}\n"
    fd, temporary = tempfile.mkstemp(prefix=".smtp-env-", dir=destination.parent)
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
            raise ConfigError("Usage: smtp-config.py export | apply PAYLOAD ENV_FILE")
    except ConfigError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (ValueError, OSError):
        # JSON parse errors and OS errors may contain secret input or paths. Keep CI logs generic.
        print("Invalid SMTP configuration. Check production secrets and variables; server settings were not changed.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
