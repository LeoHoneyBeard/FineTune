from typing import Literal

from pydantic import BaseModel, Field


class Finding(BaseModel):
    type: str
    value: str
    start: int
    end: int
    redaction: str


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1)


class ChatRequest(BaseModel):
    message: str | None = None
    messages: list[ChatMessage] | None = None
    mode: Literal["block", "mask"] = "block"
    model: str = "gpt-4o-mini"

    @property
    def prompt_text(self) -> str:
        if self.messages:
            return "\n\n".join(f"{item.role.upper()}:\n{item.content}" for item in self.messages)
        return self.message or ""


class ChatResponse(BaseModel):
    status: Literal["ok", "blocked", "error"]
    action: str
    response: str | None = None
    warning: str | None = None
    input_findings: list[Finding] = Field(default_factory=list)
    output_findings: list[Finding] = Field(default_factory=list)
