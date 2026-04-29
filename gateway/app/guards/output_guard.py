from app.models import Finding

from .detectors import find_output_risks


def inspect_output(response: str) -> list[Finding]:
    return find_output_risks(response)
