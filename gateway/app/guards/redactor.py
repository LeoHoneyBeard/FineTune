from app.models import Finding


def redact_text(text: str, findings: list[Finding]) -> str:
    if not findings:
        return text

    result: list[str] = []
    cursor = 0
    for finding in sorted(findings, key=lambda item: item.start):
        if finding.start < cursor:
            continue
        result.append(text[cursor:finding.start])
        result.append(finding.redaction)
        cursor = finding.end
    result.append(text[cursor:])
    return "".join(result)
