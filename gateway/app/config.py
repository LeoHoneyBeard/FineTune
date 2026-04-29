import os
from dataclasses import dataclass
from pathlib import Path


GATEWAY_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = GATEWAY_ROOT.parent


@dataclass(frozen=True)
class Settings:
    openai_api_key: str | None
    openai_api_base_url: str
    audit_log_path: Path


def get_settings() -> Settings:
    audit_log_path = os.getenv("GATEWAY_AUDIT_LOG_PATH")
    return Settings(
        openai_api_key=os.getenv("OPENAI_API_KEY") or read_local_property("openai.api.token"),
        openai_api_base_url=os.getenv("OPENAI_API_BASE_URL", "https://api.openai.com/v1"),
        audit_log_path=Path(audit_log_path) if audit_log_path else GATEWAY_ROOT / "logs" / "audit.jsonl",
    )


def read_local_property(key: str, properties_path: Path | None = None) -> str | None:
    path = properties_path or PROJECT_ROOT / "local.properties"
    if not path.exists():
        return None

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        separator_index = _property_separator_index(line)
        if separator_index < 0:
            continue
        name = line[:separator_index].strip()
        value = line[separator_index + 1 :].strip()
        if name == key and value:
            return value
    return None


def _property_separator_index(line: str) -> int:
    equals_index = line.find("=")
    colon_index = line.find(":")
    candidates = [index for index in (equals_index, colon_index) if index >= 0]
    return min(candidates) if candidates else -1
