import base64
import re
from dataclasses import dataclass
from typing import Iterable

from app.models import Finding


@dataclass(frozen=True)
class DetectorPattern:
    finding_type: str
    redaction: str
    regex: re.Pattern[str]


OPENAI_API_KEY = "OPENAI_API_KEY"
GITHUB_TOKEN = "GITHUB_TOKEN"
AWS_ACCESS_KEY = "AWS_ACCESS_KEY"
EMAIL = "EMAIL"
CREDIT_CARD = "CREDIT_CARD"
PHONE = "PHONE"
BASE64_SECRET = "BASE64_SECRET"
SYSTEM_PROMPT_LEAK = "SYSTEM_PROMPT_LEAK"
SUSPICIOUS_URL = "SUSPICIOUS_URL"
DANGEROUS_COMMAND = "DANGEROUS_COMMAND"

PATTERNS: tuple[DetectorPattern, ...] = (
    DetectorPattern(
        OPENAI_API_KEY,
        "[REDACTED_API_KEY]",
        re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{6,}", re.IGNORECASE),
    ),
    DetectorPattern(
        GITHUB_TOKEN,
        "[REDACTED_GITHUB_TOKEN]",
        re.compile(r"ghp_[A-Za-z0-9_]{10,}", re.IGNORECASE),
    ),
    DetectorPattern(
        AWS_ACCESS_KEY,
        "[REDACTED_AWS_KEY]",
        re.compile(r"AKIA[0-9A-Z]{16}"),
    ),
    DetectorPattern(
        EMAIL,
        "[REDACTED_EMAIL]",
        re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE),
    ),
)

SPLIT_OPENAI_KEY_PATTERN = DetectorPattern(
    OPENAI_API_KEY,
    "[REDACTED_API_KEY]",
    re.compile(
        r"sk-\s*['\"]?\s*(?:\+\s*)?['\"]?\s*proj-[A-Za-z0-9_-]{6,}",
        re.IGNORECASE,
    ),
)

CARD_CANDIDATE_RE = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}\d(?!\d)")
PHONE_CANDIDATE_RE = re.compile(r"(?<![\w])(?:\+?\d[\d\s().-]{7,}\d)(?![\w])")
BASE64_CANDIDATE_RE = re.compile(r"(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{16,}={0,2}(?![A-Za-z0-9+/=])")

SYSTEM_PROMPT_RE = re.compile(
    r"\b(system prompt|developer message|hidden instructions|internal instructions|"
    r"You are ChatGPT|ignore previous instructions)\b",
    re.IGNORECASE,
)
URL_RE = re.compile(r"https?://[^\s<>\"]+", re.IGNORECASE)
DANGEROUS_COMMAND_RE = re.compile(
    r"(rm\s+-rf\s+/|curl\b.+\|\s*bash|wget\b|powershell\s+-enc\b|chmod\s+777\b|"
    r"sudo\b|nc\s+-e\b|bash\s+-i\b|/etc/passwd)",
    re.IGNORECASE,
)


def find_input_secrets(text: str) -> list[Finding]:
    findings = _find_pattern_matches(text, (*PATTERNS, SPLIT_OPENAI_KEY_PATTERN))
    findings.extend(_find_credit_cards(text))
    findings.extend(_find_phones(text))
    findings.extend(_find_base64_secrets(text))
    return _dedupe_and_sort(findings)


def find_output_risks(text: str) -> list[Finding]:
    findings = find_input_secrets(text)
    findings.extend(_find_simple(text, SYSTEM_PROMPT_RE, SYSTEM_PROMPT_LEAK, "[REDACTED_SYSTEM_PROMPT]"))
    findings.extend(_find_dangerous_commands(text))
    findings.extend(_find_suspicious_urls(text))
    return _dedupe_and_sort(findings)


def _find_pattern_matches(text: str, patterns: Iterable[DetectorPattern]) -> list[Finding]:
    findings: list[Finding] = []
    for pattern in patterns:
        for match in pattern.regex.finditer(text):
            findings.append(
                Finding(
                    type=pattern.finding_type,
                    value=match.group(0),
                    start=match.start(),
                    end=match.end(),
                    redaction=pattern.redaction,
                )
            )
    return findings


def _find_credit_cards(text: str) -> list[Finding]:
    findings: list[Finding] = []
    for match in CARD_CANDIDATE_RE.finditer(text):
        value = match.group(0)
        digits = re.sub(r"\D", "", value)
        if 13 <= len(digits) <= 19 and _passes_luhn(digits):
            findings.append(
                Finding(
                    type=CREDIT_CARD,
                    value=value,
                    start=match.start(),
                    end=match.end(),
                    redaction="[REDACTED_CARD]",
                )
            )
    return findings


def _find_phones(text: str) -> list[Finding]:
    findings: list[Finding] = []
    for match in PHONE_CANDIDATE_RE.finditer(text):
        value = match.group(0)
        digits = re.sub(r"\D", "", value)
        if 10 <= len(digits) <= 15 and not _passes_luhn(digits):
            findings.append(
                Finding(
                    type=PHONE,
                    value=value,
                    start=match.start(),
                    end=match.end(),
                    redaction="[REDACTED_PHONE]",
                )
            )
    return findings


def _find_base64_secrets(text: str) -> list[Finding]:
    findings: list[Finding] = []
    for match in BASE64_CANDIDATE_RE.finditer(text):
        value = match.group(0)
        decoded = _decode_base64(value)
        if not decoded:
            continue
        decoded_findings = _find_pattern_matches(decoded, PATTERNS)
        if decoded_findings:
            findings.append(
                Finding(
                    type=BASE64_SECRET,
                    value=value,
                    start=match.start(),
                    end=match.end(),
                    redaction="[REDACTED_BASE64_SECRET]",
                )
            )
    return findings


def _find_simple(text: str, regex: re.Pattern[str], finding_type: str, redaction: str) -> list[Finding]:
    return [
        Finding(type=finding_type, value=match.group(0), start=match.start(), end=match.end(), redaction=redaction)
        for match in regex.finditer(text)
    ]


def _find_dangerous_commands(text: str) -> list[Finding]:
    return _find_simple(text, DANGEROUS_COMMAND_RE, DANGEROUS_COMMAND, "[REDACTED_DANGEROUS_COMMAND]")


def _find_suspicious_urls(text: str) -> list[Finding]:
    findings: list[Finding] = []
    suspicious_hosts = ("bit.ly", "tinyurl.com", "pastebin.com", "ngrok", "raw.githubusercontent.com")
    suspicious_query = re.compile(r"[?&](token|key|api_key|apikey|secret|password)=", re.IGNORECASE)
    raw_ip = re.compile(r"https?://\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?(?:/|$)", re.IGNORECASE)
    for match in URL_RE.finditer(text):
        value = match.group(0)
        lower = value.lower()
        if any(host in lower for host in suspicious_hosts) or suspicious_query.search(value) or raw_ip.search(value):
            findings.append(
                Finding(
                    type=SUSPICIOUS_URL,
                    value=value,
                    start=match.start(),
                    end=match.end(),
                    redaction="[REDACTED_URL]",
                )
            )
    return findings


def _decode_base64(value: str) -> str | None:
    try:
        padded = value + ("=" * (-len(value) % 4))
        raw = base64.b64decode(padded, validate=True)
        decoded = raw.decode("utf-8")
    except Exception:
        return None
    if not decoded or "\x00" in decoded:
        return None
    return decoded


def _passes_luhn(digits: str) -> bool:
    total = 0
    reverse_digits = digits[::-1]
    for index, char in enumerate(reverse_digits):
        value = int(char)
        if index % 2 == 1:
            value *= 2
            if value > 9:
                value -= 9
        total += value
    return total % 10 == 0


def _dedupe_and_sort(findings: list[Finding]) -> list[Finding]:
    findings.sort(key=lambda finding: (finding.start, -(finding.end - finding.start)))
    result: list[Finding] = []
    for finding in findings:
        if any(_overlaps(finding, kept) for kept in result):
            continue
        result.append(finding)
    return result


def _overlaps(left: Finding, right: Finding) -> bool:
    return left.start < right.end and right.start < left.end
