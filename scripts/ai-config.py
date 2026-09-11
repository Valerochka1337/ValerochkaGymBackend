#!/usr/bin/env python3
"""Transfer only AI settings; never source dotenv or print credentials in diagnostics."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile

REQUIRED_KEYS = {"AI_ENABLED", "AI_PROVIDER", "AI_BASE_URL", "AI_API_KEY", "AI_TEXT_MODEL", "AI_VISION_MODEL"}

OPTIONAL_KEYS = {"AI_COACH_MODEL", "AI_COACH_MODELS"}
KEYS = REQUIRED_KEYS | OPTIONAL_KEYS

class ConfigError(ValueError):
    pass

def validate(config):
    if not isinstance(config, dict) or set(config) - KEYS:
        raise ConfigError("Unexpected AI configuration keys")
    for key, value in config.items():
        if not isinstance(value, str) or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ConfigError("AI settings must be single-line values")
    if config == {"AI_ENABLED": "false"}:
        return config
    if not REQUIRED_KEYS.issubset(config) or config["AI_ENABLED"] != "true" or config["AI_PROVIDER"] != "openai":
        raise ConfigError("Incomplete AI configuration")
    if any(not config[key].strip() for key in REQUIRED_KEYS):
        raise ConfigError("Missing AI production setting")
    default_model = config.get("AI_COACH_MODEL", "")
    default_model = default_model if default_model.strip() else config["AI_TEXT_MODEL"]
    coach_models = list(dict.fromkeys([default_model] + [value.strip() for value in config.get("AI_COACH_MODELS", "").split(",") if value.strip()]))
    if len(coach_models) > 20 or any(len(value) > 200 for value in coach_models):
        raise ConfigError("Coach model catalogue exceeds limits")
    from urllib.parse import urlsplit
    try:
        uri = urlsplit(config["AI_BASE_URL"])
        valid = uri.scheme == "https" and uri.hostname and not uri.username and not uri.password and not uri.query and not uri.fragment and uri.path in ("", "/", "/v1", "/v1/") and (uri.port is None or 1 <= uri.port <= 65535)
    except ValueError:
        valid = False
    if not valid:
        raise ConfigError("AI endpoint must be fixed HTTPS origin or v1 base")
    return config

def from_environment(env):
    if env.get("AI_ENABLED", "false") in ("", "false"):
        return {"AI_ENABLED": "false"}
    return validate({key: env.get(key, "") for key in REQUIRED_KEYS} | {key: env[key] for key in OPTIONAL_KEYS if env.get(key, "").strip()})


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
        if match is None or match.group(1) not in KEYS:
            retained.append(line)
    content = "".join(retained)
    if content and not content.endswith("\n"):
        content += "\n"
    for key, value in sorted(config.items()):
        # Compose expands $ in double quotes. $$ preserves literal dollars, while JSON
        # quoting handles quotes and backslashes. This file must not be sourced by a shell.
        content += f"{key}={json.dumps(value.replace('$', '$$'), ensure_ascii=False)}\n"
    fd, temporary = tempfile.mkstemp(prefix=".ai-env-", dir=destination.parent)
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
            raise ConfigError("Usage: ai-config.py export | apply PAYLOAD ENV_FILE")
    except ConfigError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (ValueError, OSError):
        # JSON parse errors and OS errors may contain secret input or paths. Keep CI logs generic.
        print("Invalid AI configuration. Check production secrets and variables; server settings were not changed.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
