import json
from uuid import uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

from .audit.logger import AuditLogger
from .config import Settings, get_settings
from .guards.input_guard import inspect_input
from .guards.output_guard import inspect_output
from .llm_client import LlmClientError, MissingApiKeyError, OpenAiChatClient, OpenAiProxyClient
from .models import ChatRequest, ChatResponse

app = FastAPI(title="FineTune LLM Gateway")


def get_llm_client(settings: Settings = Depends(get_settings)) -> OpenAiChatClient:
    return OpenAiChatClient(settings)


def get_proxy_client(settings: Settings = Depends(get_settings)) -> OpenAiProxyClient:
    return OpenAiProxyClient(settings)


def get_audit_logger(settings: Settings = Depends(get_settings)) -> AuditLogger:
    return AuditLogger(settings.audit_log_path)


@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    llm_client: OpenAiChatClient = Depends(get_llm_client),
    audit_logger: AuditLogger = Depends(get_audit_logger),
) -> JSONResponse:
    request_id = str(uuid4())
    prompt_text = request.prompt_text
    if not prompt_text.strip():
        response = ChatResponse(
            status="error",
            warning="Either message or messages must be provided",
            action="error",
        )
        return JSONResponse(status_code=422, content=_dump_response(response))

    input_result = inspect_input(prompt_text, request.mode)

    if input_result.action == "blocked":
        response = ChatResponse(
            status="blocked",
            warning="Input contains sensitive data",
            input_findings=input_result.findings,
            action="blocked",
        )
        audit_logger.log(
            request_id=request_id,
            original_prompt=prompt_text,
            redacted_prompt=input_result.prompt,
            input_findings=input_result.findings,
            output_findings=[],
            llm_called=False,
            action=response.action,
            final_status=response.status,
        )
        return JSONResponse(status_code=200, content=_dump_response(response))

    llm_called = False
    try:
        llm_called = True
        model_response = await llm_client.chat(
            input_result.prompt,
            request.model,
            messages=_redacted_messages(request, input_result.prompt),
        )
    except MissingApiKeyError as error:
        response = ChatResponse(
            status="error",
            warning=str(error),
            input_findings=input_result.findings,
            action="error",
        )
        audit_logger.log(
            request_id=request_id,
            original_prompt=prompt_text,
            redacted_prompt=input_result.prompt,
            input_findings=input_result.findings,
            output_findings=[],
            llm_called=False,
            action=response.action,
            final_status=response.status,
        )
        return JSONResponse(status_code=503, content=_dump_response(response))
    except LlmClientError as error:
        response = ChatResponse(
            status="error",
            warning=str(error),
            input_findings=input_result.findings,
            action="llm_error",
        )
        audit_logger.log(
            request_id=request_id,
            original_prompt=prompt_text,
            redacted_prompt=input_result.prompt,
            input_findings=input_result.findings,
            output_findings=[],
            llm_called=llm_called,
            action=response.action,
            final_status=response.status,
        )
        return JSONResponse(status_code=502, content=_dump_response(response))

    output_findings = inspect_output(model_response)
    if output_findings:
        response = ChatResponse(
            status="blocked",
            warning="Model output failed safety checks",
            input_findings=input_result.findings,
            output_findings=output_findings,
            action="output_blocked",
        )
        audit_logger.log(
            request_id=request_id,
            original_prompt=prompt_text,
            redacted_prompt=input_result.prompt,
            input_findings=input_result.findings,
            output_findings=output_findings,
            llm_called=llm_called,
            action=response.action,
            final_status=response.status,
        )
        return JSONResponse(status_code=200, content=_dump_response(response))

    response = ChatResponse(
        status="ok",
        response=model_response,
        input_findings=input_result.findings,
        output_findings=[],
        action=input_result.action,
    )
    audit_logger.log(
        request_id=request_id,
        original_prompt=prompt_text,
        redacted_prompt=input_result.prompt,
        input_findings=input_result.findings,
        output_findings=[],
        llm_called=llm_called,
        action=response.action,
        final_status=response.status,
    )
    return JSONResponse(status_code=200, content=_dump_response(response))


@app.api_route("/v1/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"])
async def openai_compatible_proxy(
    path: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    proxy_client: OpenAiProxyClient = Depends(get_proxy_client),
    audit_logger: AuditLogger = Depends(get_audit_logger),
) -> Response:
    body = await request.body()
    request_id = str(uuid4())
    prompt_text = _compat_prompt_text(path, body)
    input_result = inspect_input(prompt_text, settings.compat_input_mode) if prompt_text else None

    if input_result and input_result.action == "blocked":
        audit_logger.log(
            request_id=request_id,
            original_prompt=prompt_text,
            redacted_prompt=input_result.prompt,
            input_findings=input_result.findings,
            output_findings=[],
            llm_called=False,
            action="blocked",
            final_status="blocked",
        )
        return _openai_error_response(
            400,
            "Input contains sensitive data",
            "invalid_request_error",
            "input_guard_blocked",
        )

    if input_result and input_result.action == "masked":
        body = _masked_compat_body(path, body, input_result.prompt)

    try:
        if _is_stream_request(body):
            upstream_response, chunks = await proxy_client.stream(
                request.method,
                path,
                request.url.query.encode("utf-8"),
                body,
                request.headers,
            )
            audit_logger.log(
                request_id=request_id,
                original_prompt=prompt_text,
                redacted_prompt=input_result.prompt if input_result else prompt_text,
                input_findings=input_result.findings if input_result else [],
                output_findings=[],
                llm_called=True,
                action=input_result.action if input_result else "proxied",
                final_status="ok",
            )
            return StreamingResponse(
                chunks,
                status_code=upstream_response.status_code,
                headers=_response_headers(upstream_response.headers),
                media_type=upstream_response.headers.get("content-type"),
            )

        upstream_response = await proxy_client.request(
            request.method,
            path,
            request.url.query.encode("utf-8"),
            body,
            request.headers,
        )
    except MissingApiKeyError as error:
        return _openai_error_response(503, str(error), "server_error", "missing_api_key")
    except LlmClientError as error:
        return _openai_error_response(502, str(error), "server_error", "upstream_error")

    audit_logger.log(
        request_id=request_id,
        original_prompt=prompt_text,
        redacted_prompt=input_result.prompt if input_result else prompt_text,
        input_findings=input_result.findings if input_result else [],
        output_findings=[],
        llm_called=True,
        action=input_result.action if input_result else "proxied",
        final_status="ok" if upstream_response.status_code < 400 else "error",
    )
    return Response(
        content=upstream_response.content,
        status_code=upstream_response.status_code,
        headers=_response_headers(upstream_response.headers),
        media_type=upstream_response.headers.get("content-type"),
    )


def _dump_response(response: ChatResponse) -> dict:
    if hasattr(response, "model_dump"):
        return response.model_dump(exclude_none=True)
    return response.dict(exclude_none=True)


def _redacted_messages(request: ChatRequest, redacted_prompt: str):
    if not request.messages:
        return None
    if not request.message and redacted_prompt == request.prompt_text:
        return request.messages
    return None


def _compat_prompt_text(path: str, body: bytes) -> str:
    if not body or path not in {"chat/completions", "responses"}:
        return ""
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return ""
    return "\n\n".join(_extract_strings(payload))


def _extract_strings(value) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        strings: list[str] = []
        for item in value:
            strings.extend(_extract_strings(item))
        return strings
    if isinstance(value, dict):
        strings: list[str] = []
        for item in value.values():
            strings.extend(_extract_strings(item))
        return strings
    return []


def _masked_compat_body(path: str, body: bytes, redacted_prompt: str) -> bytes:
    if path != "chat/completions":
        return body
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return body
    if "messages" in payload:
        payload["messages"] = [{"role": "user", "content": redacted_prompt}]
        return json.dumps(payload).encode("utf-8")
    return body


def _is_stream_request(body: bytes) -> bool:
    if not body:
        return False
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False
    return payload.get("stream") is True


def _response_headers(headers) -> dict[str, str]:
    excluded = {
        "connection",
        "content-encoding",
        "content-length",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
    return {name: value for name, value in headers.items() if name.lower() not in excluded}


def _openai_error_response(status_code: int, message: str, error_type: str, code: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "error": {
                "message": message,
                "type": error_type,
                "param": None,
                "code": code,
            }
        },
    )
