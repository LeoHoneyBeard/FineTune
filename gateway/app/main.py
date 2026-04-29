from uuid import uuid4

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse

from .audit.logger import AuditLogger
from .config import Settings, get_settings
from .guards.input_guard import inspect_input
from .guards.output_guard import inspect_output
from .llm_client import LlmClientError, MissingApiKeyError, OpenAiChatClient
from .models import ChatRequest, ChatResponse

app = FastAPI(title="FineTune LLM Gateway")


def get_llm_client(settings: Settings = Depends(get_settings)) -> OpenAiChatClient:
    return OpenAiChatClient(settings)


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
