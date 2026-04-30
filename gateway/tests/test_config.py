from app.config import get_settings, read_local_property


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


def test_get_settings_reads_gateway_mode_from_local_properties(monkeypatch, tmp_path):
    properties = tmp_path / "local.properties"
    properties.write_text(
        """
        openai.api.token=sk-test-local
        llm.gateway.mode=mask
        """.strip(),
        encoding="utf-8",
    )

    monkeypatch.delenv("GATEWAY_COMPAT_INPUT_MODE", raising=False)
    monkeypatch.setattr("app.config.PROJECT_ROOT", tmp_path)

    assert get_settings().compat_input_mode == "mask"


def test_get_settings_env_gateway_mode_overrides_local_properties(monkeypatch, tmp_path):
    properties = tmp_path / "local.properties"
    properties.write_text("llm.gateway.mode=mask", encoding="utf-8")

    monkeypatch.setenv("GATEWAY_COMPAT_INPUT_MODE", "block")
    monkeypatch.setattr("app.config.PROJECT_ROOT", tmp_path)

    assert get_settings().compat_input_mode == "block"
