from dataclasses import dataclass

from app.models import Finding

from .detectors import find_input_secrets
from .redactor import redact_text


@dataclass(frozen=True)
class InputGuardResult:
    action: str
    prompt: str
    findings: list[Finding]


def inspect_input(prompt: str, mode: str) -> InputGuardResult:
    findings = find_input_secrets(prompt)
    if findings and mode == "block":
        return InputGuardResult(action="blocked", prompt=prompt, findings=findings)
    if findings and mode == "mask":
        return InputGuardResult(action="masked", prompt=redact_text(prompt, findings), findings=findings)
    return InputGuardResult(action="allowed", prompt=prompt, findings=findings)
