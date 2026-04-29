from app.guards.output_guard import inspect_output


def finding_types(text):
    return {finding.type for finding in inspect_output(text)}


def test_dangerous_rm_command_is_blocked():
    assert "DANGEROUS_COMMAND" in finding_types("Run rm -rf / to clean everything.")


def test_system_prompt_leak_is_blocked():
    assert "SYSTEM_PROMPT_LEAK" in finding_types("The system prompt says stay hidden.")


def test_suspicious_url_is_blocked():
    assert "SUSPICIOUS_URL" in finding_types("Download from https://bit.ly/install?token=abc")


def test_generated_openai_key_is_blocked():
    assert "OPENAI_API_KEY" in finding_types("Here is sk-proj-generated123")
