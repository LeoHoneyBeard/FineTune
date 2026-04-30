import asyncio
from collections.abc import AsyncIterator, Mapping

import httpx

from .config import Settings
from .models import ChatMessage


class MissingApiKeyError(RuntimeError):
    pass


class LlmClientError(RuntimeError):
    pass


class OpenAiChatClient:
    _retry_delays = (0.5, 1.0, 2.0)
    _retryable_status_codes = {429, 500, 502, 503, 504}
    _retryable_http_errors = (
        httpx.ConnectError,
        httpx.ReadError,
        httpx.RemoteProtocolError,
        httpx.TimeoutException,
        httpx.WriteError,
    )

    def __init__(self, settings: Settings):
        self._settings = settings

    async def chat(self, message: str, model: str, messages: list[ChatMessage] | None = None) -> str:
        if not self._settings.openai_api_key:
            raise MissingApiKeyError("OpenAI API key is not configured in OPENAI_API_KEY or local.properties")

        url = f"{self._settings.openai_api_base_url.rstrip('/')}/chat/completions"
        payload = {
            "model": model,
            "messages": [
                {"role": item.role, "content": item.content}
                for item in messages
            ] if messages else [{"role": "user", "content": message}],
        }
        headers = {
            "Authorization": f"Bearer {self._settings.openai_api_key}",
            "Content-Type": "application/json",
        }
        data = await self._post_with_retries(url, payload, headers)

        choices = data.get("choices") or []
        if not choices:
            return ""
        message_data = choices[0].get("message") or {}
        return message_data.get("content") or ""

    async def _post_with_retries(self, url: str, payload: dict, headers: dict[str, str]) -> dict:
        attempts = len(self._retry_delays) + 1
        last_error: Exception | None = None
        async with httpx.AsyncClient(timeout=60, http2=False) as client:
            for attempt in range(attempts):
                try:
                    response = await client.post(url, json=payload, headers=headers)
                    response.raise_for_status()
                    return response.json()
                except httpx.HTTPStatusError as error:
                    status_code = error.response.status_code
                    if status_code not in self._retryable_status_codes or attempt == attempts - 1:
                        response_text = error.response.text
                        raise LlmClientError(f"OpenAI request failed: HTTP {status_code}: {response_text}") from error
                    last_error = error
                except self._retryable_http_errors as error:
                    if attempt == attempts - 1:
                        raise LlmClientError(f"OpenAI request failed after {attempts} attempts: {error}") from error
                    last_error = error
                except httpx.HTTPError as error:
                    raise LlmClientError(f"OpenAI request failed: {error}") from error

                await asyncio.sleep(self._retry_delays[attempt])

        raise LlmClientError(f"OpenAI request failed after {attempts} attempts: {last_error}")


class OpenAiProxyClient:
    _hop_by_hop_headers = {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "host",
        "content-length",
        "authorization",
    }

    def __init__(self, settings: Settings):
        self._settings = settings

    def upstream_url(self, path: str) -> str:
        return f"{self._settings.openai_api_base_url.rstrip('/')}/{path.lstrip('/')}"

    def upstream_headers(self, incoming_headers: Mapping[str, str]) -> dict[str, str]:
        if not self._settings.openai_api_key:
            raise MissingApiKeyError("OpenAI API key is not configured in OPENAI_API_KEY or local.properties")

        headers = {
            name: value
            for name, value in incoming_headers.items()
            if name.lower() not in self._hop_by_hop_headers
        }
        headers["Authorization"] = f"Bearer {self._settings.openai_api_key}"
        return headers

    async def request(
        self,
        method: str,
        path: str,
        query: bytes,
        body: bytes,
        incoming_headers: Mapping[str, str],
    ) -> httpx.Response:
        headers = self.upstream_headers(incoming_headers)
        async with httpx.AsyncClient(timeout=None, http2=False) as client:
            return await client.request(
                method,
                self.upstream_url(path),
                params=query,
                content=body,
                headers=headers,
            )

    async def stream(
        self,
        method: str,
        path: str,
        query: bytes,
        body: bytes,
        incoming_headers: Mapping[str, str],
    ) -> tuple[httpx.Response, AsyncIterator[bytes]]:
        headers = self.upstream_headers(incoming_headers)
        client = httpx.AsyncClient(timeout=None, http2=False)
        request = client.build_request(
            method,
            self.upstream_url(path),
            params=query,
            content=body,
            headers=headers,
        )
        response = await client.send(request, stream=True)

        async def chunks() -> AsyncIterator[bytes]:
            try:
                async for chunk in response.aiter_raw():
                    yield chunk
            finally:
                await response.aclose()
                await client.aclose()

        return response, chunks()
