from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.audit.logger import AuditLogger
from app.config import Settings
from app.llm_client import LlmClientError
from app.main import app, get_audit_logger, get_llm_client, get_proxy_client, get_settings


class FakeLlmClient:
    def __init__(self, response: str = "safe response"):
        self.response = response
        self.called = False
        self.messages: list[str] = []

    async def chat(self, message: str, model: str, messages=None) -> str:
        self.called = True
        self.messages.append(message)
        return self.response


class FailingLlmClient:
    async def chat(self, message: str, model: str, messages=None) -> str:
        raise LlmClientError("OpenAI request failed: HTTP 400: bad request")


class FakeProxyClient:
    def __init__(self):
        self.called = False
        self.requests = []

    async def request(self, method: str, path: str, query: bytes, body: bytes, incoming_headers):
        self.called = True
        self.requests.append(
            {
                "method": method,
                "path": path,
                "query": query,
                "body": body,
                "authorization": incoming_headers.get("authorization"),
            }
        )
        return httpx.Response(
            200,
            json={
                "id": "chatcmpl-test",
                "object": "chat.completion",
                "choices": [{"message": {"role": "assistant", "content": "ok"}}],
            },
            request=httpx.Request(method, f"https://api.openai.test/{path}"),
        )


@pytest.fixture
def audit_path(tmp_path: Path) -> Path:
    return tmp_path / "audit.jsonl"


@pytest.fixture
def client(audit_path: Path):
    fake = FakeLlmClient()
    app.dependency_overrides[get_llm_client] = lambda: fake
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)
    with TestClient(app) as test_client:
        test_client.fake_llm = fake
        yield test_client
    app.dependency_overrides.clear()


def test_input_block_mode_does_not_call_llm(client):
    response = client.post(
        "/chat",
        json={"message": "secret sk-proj-abc123", "mode": "block", "model": "gpt-4o-mini"},
    )

    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "blocked"
    assert body["action"] == "blocked"
    assert body["input_findings"][0]["type"] == "OPENAI_API_KEY"
    assert client.fake_llm.called is False


def test_input_mask_mode_calls_llm_with_redacted_prompt(client):
    response = client.post(
        "/chat",
        json={"message": "secret sk-proj-abc123", "mode": "mask", "model": "gpt-4o-mini"},
    )

    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "ok"
    assert body["action"] == "masked"
    assert client.fake_llm.called is True
    assert client.fake_llm.messages == ["secret [REDACTED_API_KEY]"]


def test_output_blocked_does_not_return_raw_dangerous_response(audit_path):
    fake = FakeLlmClient("run rm -rf / now")
    app.dependency_overrides[get_llm_client] = lambda: fake
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/chat",
            json={"message": "safe prompt", "mode": "block", "model": "gpt-4o-mini"},
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "blocked"
    assert body["action"] == "output_blocked"
    assert "run rm -rf / now" not in str(body)
    assert body["output_findings"][0]["type"] == "DANGEROUS_COMMAND"


def test_output_blocked_for_powershell_encoded_command(audit_path):
    fake = FakeLlmClient("Use powershell -enc SQBFAFgA")
    app.dependency_overrides[get_llm_client] = lambda: fake
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/chat",
            json={"message": "show a command example", "mode": "block", "model": "gpt-4o-mini"},
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "blocked"
    assert body["action"] == "output_blocked"
    assert body["output_findings"][0]["type"] == "DANGEROUS_COMMAND"


def test_output_blocked_for_generated_openai_key(audit_path):
    fake = FakeLlmClient("Here is sk-proj-generated1234567890")
    app.dependency_overrides[get_llm_client] = lambda: fake
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/chat",
            json={"message": "show a fake key", "mode": "block", "model": "gpt-4o-mini"},
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "blocked"
    assert body["action"] == "output_blocked"
    assert body["output_findings"][0]["type"] == "OPENAI_API_KEY"


def test_messages_payload_is_accepted(client):
    response = client.post(
        "/chat",
        json={
            "messages": [
                {"role": "system", "content": "Be concise."},
                {"role": "user", "content": "Say hi."},
            ],
            "mode": "block",
            "model": "gpt-4o-mini",
        },
    )

    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "ok"
    assert client.fake_llm.called is True
    assert "SYSTEM:\nBe concise." in client.fake_llm.messages[0]


def test_upstream_llm_error_returns_json_error(audit_path):
    app.dependency_overrides[get_llm_client] = lambda: FailingLlmClient()
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/chat",
            json={"message": "safe prompt", "mode": "block", "model": "gpt-4o-mini"},
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 502
    assert body["status"] == "error"
    assert body["action"] == "llm_error"
    assert "OpenAI request failed" in body["warning"]


def test_openai_compatible_chat_completion_proxy_forwards_raw_request(audit_path):
    fake_proxy = FakeProxyClient()
    app.dependency_overrides[get_proxy_client] = lambda: fake_proxy
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/v1/chat/completions?timeout=30",
            headers={"Authorization": "Bearer codex-client-token"},
            json={
                "model": "gpt-5.5",
                "messages": [{"role": "user", "content": "Say hi."}],
            },
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 200
    assert body["object"] == "chat.completion"
    assert fake_proxy.called is True
    assert fake_proxy.requests[0]["method"] == "POST"
    assert fake_proxy.requests[0]["path"] == "chat/completions"
    assert fake_proxy.requests[0]["query"] == b"timeout=30"
    assert b"gpt-5.5" in fake_proxy.requests[0]["body"]
    assert fake_proxy.requests[0]["authorization"] == "Bearer codex-client-token"


def test_openai_compatible_proxy_blocks_sensitive_input(audit_path):
    fake_proxy = FakeProxyClient()
    app.dependency_overrides[get_proxy_client] = lambda: fake_proxy
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)
    app.dependency_overrides[get_settings] = lambda: Settings(
        openai_api_key="sk-test",
        openai_api_base_url="https://api.openai.test/v1",
        audit_log_path=audit_path,
        compat_input_mode="block",
    )

    with TestClient(app) as test_client:
        response = test_client.post(
            "/v1/chat/completions",
            json={
                "model": "gpt-5.5",
                "messages": [{"role": "user", "content": "secret sk-proj-abc123"}],
            },
        )

    app.dependency_overrides.clear()
    body = response.json()
    assert response.status_code == 400
    assert body["error"]["code"] == "input_guard_blocked"
    assert fake_proxy.called is False


def test_openai_compatible_responses_proxy_forwards_request(audit_path):
    fake_proxy = FakeProxyClient()
    app.dependency_overrides[get_proxy_client] = lambda: fake_proxy
    app.dependency_overrides[get_audit_logger] = lambda: AuditLogger(audit_path)

    with TestClient(app) as test_client:
        response = test_client.post(
            "/v1/responses",
            json={"model": "gpt-5.5", "input": "Say hi."},
        )

    app.dependency_overrides.clear()
    assert response.status_code == 200
    assert fake_proxy.called is True
    assert fake_proxy.requests[0]["path"] == "responses"
