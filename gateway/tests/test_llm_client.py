import httpx
import pytest

from app.config import Settings
from app.llm_client import LlmClientError, OpenAiChatClient


@pytest.mark.anyio
async def test_openai_client_retries_transient_disconnect(monkeypatch, tmp_path):
    attempts = {"count": 0}

    async def fake_post(self, url, json, headers):
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise httpx.RemoteProtocolError("Server disconnected without sending a response.")
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "ok"}}]},
            request=httpx.Request("POST", url),
        )

    async def no_sleep(delay):
        return None

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    monkeypatch.setattr("app.llm_client.asyncio.sleep", no_sleep)
    client = OpenAiChatClient(
        Settings(
            openai_api_key="sk-test",
            openai_api_base_url="https://api.openai.com/v1",
            audit_log_path=tmp_path / "audit.jsonl",
            compat_input_mode="block",
        )
    )

    assert await client.chat("hello", "gpt-4o-mini") == "ok"
    assert attempts["count"] == 2


@pytest.mark.anyio
async def test_openai_client_retries_429(monkeypatch, tmp_path):
    attempts = {"count": 0}

    async def fake_post(self, url, json, headers):
        attempts["count"] += 1
        request = httpx.Request("POST", url)
        if attempts["count"] == 1:
            return httpx.Response(429, text="rate limited", request=request)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "ok"}}]},
            request=request,
        )

    async def no_sleep(delay):
        return None

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    monkeypatch.setattr("app.llm_client.asyncio.sleep", no_sleep)
    client = OpenAiChatClient(
        Settings(
            openai_api_key="sk-test",
            openai_api_base_url="https://api.openai.com/v1",
            audit_log_path=tmp_path / "audit.jsonl",
            compat_input_mode="block",
        )
    )

    assert await client.chat("hello", "gpt-4o-mini") == "ok"
    assert attempts["count"] == 2


@pytest.mark.anyio
async def test_openai_client_does_not_retry_401(monkeypatch, tmp_path):
    attempts = {"count": 0}

    async def fake_post(self, url, json, headers):
        attempts["count"] += 1
        return httpx.Response(401, text="unauthorized", request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    client = OpenAiChatClient(
        Settings(
            openai_api_key="sk-test",
            openai_api_base_url="https://api.openai.com/v1",
            audit_log_path=tmp_path / "audit.jsonl",
            compat_input_mode="block",
        )
    )

    with pytest.raises(LlmClientError):
        await client.chat("hello", "gpt-4o-mini")
    assert attempts["count"] == 1
