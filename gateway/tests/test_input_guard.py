from app.guards.input_guard import inspect_input


def finding_types(result):
    return {finding.type for finding in result.findings}


def test_openai_key_is_detected():
    result = inspect_input("use sk-proj-abc123 please", "block")

    assert result.action == "blocked"
    assert "OPENAI_API_KEY" in finding_types(result)


def test_github_token_is_detected():
    result = inspect_input("token ghp_abcd1234567890", "block")

    assert result.action == "blocked"
    assert "GITHUB_TOKEN" in finding_types(result)


def test_aws_key_is_detected():
    result = inspect_input("aws AKIAIOSFODNN7EXAMPLE", "block")

    assert result.action == "blocked"
    assert "AWS_ACCESS_KEY" in finding_types(result)


def test_email_is_detected():
    result = inspect_input("contact admin@example.com", "block")

    assert result.action == "blocked"
    assert "EMAIL" in finding_types(result)


def test_credit_card_uses_luhn_detection():
    result = inspect_input("card 4111 1111 1111 1111", "block")

    assert result.action == "blocked"
    assert "CREDIT_CARD" in finding_types(result)


def test_phone_number_is_detected():
    result = inspect_input("call +1 (415) 555-2671", "block")

    assert result.action == "blocked"
    assert "PHONE" in finding_types(result)


def test_base64_encoded_secret_is_detected():
    result = inspect_input("encoded c2stcHJvai1hYmMxMjM=", "block")

    assert result.action == "blocked"
    assert "BASE64_SECRET" in finding_types(result)


def test_split_secret_is_detected():
    result = inspect_input('my key: "sk-" + "proj-abc123"', "block")

    assert result.action == "blocked"
    assert "OPENAI_API_KEY" in finding_types(result)


def test_clean_prompt_is_allowed():
    result = inspect_input("Summarize the support ticket.", "block")

    assert result.action == "allowed"
    assert result.findings == []


def test_mask_mode_redacts_and_allows_prompt():
    result = inspect_input("use sk-proj-abc123 please", "mask")

    assert result.action == "masked"
    assert "[REDACTED_API_KEY]" in result.prompt
    assert "sk-proj-abc123" not in result.prompt
