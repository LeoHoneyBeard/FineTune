import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.guards.redactor import redact_text
from app.models import Finding


class AuditLogger:
    def __init__(self, log_path: Path):
        self._log_path = log_path

    def log(
        self,
        *,
        request_id: str,
        original_prompt: str,
        redacted_prompt: str,
        input_findings: list[Finding],
        output_findings: list[Finding],
        llm_called: bool,
        action: str,
        final_status: str,
    ) -> None:
        safe_input_findings = _safe_findings(input_findings)
        safe_output_findings = _safe_findings(output_findings)
        original_prompt_masked = redact_text(original_prompt, input_findings)
        record: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "request_id": request_id,
            "original_prompt_masked": original_prompt_masked,
            "redacted_prompt": redacted_prompt,
            "input_findings": safe_input_findings,
            "output_findings": safe_output_findings,
            "llm_called": llm_called,
            "action": action,
            "final_status": final_status,
        }
        self._log_path.parent.mkdir(parents=True, exist_ok=True)
        with self._log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def _safe_findings(findings: list[Finding]) -> list[dict[str, Any]]:
    safe: list[dict[str, Any]] = []
    for finding in findings:
        safe.append(
            {
                "type": finding.type,
                "value_hash": hashlib.sha256(finding.value.encode("utf-8")).hexdigest(),
                "start": finding.start,
                "end": finding.end,
                "redaction": finding.redaction,
            }
        )
    return safe
