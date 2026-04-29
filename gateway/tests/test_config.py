from app.config import read_local_property


def test_read_local_property_gets_openai_token(tmp_path):
    properties = tmp_path / "local.properties"
    properties.write_text(
        """
        # local values
        openai.api.token=sk-test-local
        openai.chat.model=gpt-4o-mini
        """.strip(),
        encoding="utf-8",
    )

    assert read_local_property("openai.api.token", properties) == "sk-test-local"


def test_read_local_property_returns_none_for_missing_file(tmp_path):
    assert read_local_property("openai.api.token", tmp_path / "missing.properties") is None
