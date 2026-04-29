# FineTune LLM Gateway

Local FastAPI gateway for proxying chat prompts to the OpenAI API with input guards, output guards, redaction, and JSONL audit logs.

## Setup

From `gateway/`:

```powershell
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

The gateway reads the OpenAI key from the root project `local.properties`:

```powershell
openai.api.token=sk-...
```

`OPENAI_API_KEY` is still supported as an environment override. The server can start without either value, but `/chat` returns a clear error when a request needs to call OpenAI.

## Run

```powershell
uvicorn app.main:app --reload --port 8000
```

## Test

```powershell
pytest
```

Tests use a fake LLM client and do not call OpenAI.

## API

`POST /chat`

```json
{
  "message": "text",
  "mode": "block",
  "model": "gpt-4o-mini"
}
```

`mode=block` blocks prompts with detected sensitive data before the LLM call.

`mode=mask` replaces detected sensitive data with placeholders and sends the redacted prompt to the LLM.

Example:

```powershell
curl -X POST http://localhost:8000/chat `
  -H "Content-Type: application/json" `
  -d "{\"message\":\"Summarize this ticket\",\"mode\":\"block\",\"model\":\"gpt-4o-mini\"}"
```

Masking example:

```powershell
curl -X POST http://localhost:8000/chat `
  -H "Content-Type: application/json" `
  -d "{\"message\":\"My key is sk-proj-abc123\",\"mode\":\"mask\",\"model\":\"gpt-4o-mini\"}"
```

## Guards

Input guard detects OpenAI keys, GitHub tokens, AWS access keys, email addresses, credit card numbers with Luhn validation, phone numbers, Base64-encoded secrets, and split OpenAI keys such as `"sk-" + "proj-abc123"`.

Output guard detects generated secrets, email/card/phone data, system prompt leak indicators, suspicious URLs, and dangerous shell commands such as `rm -rf /`, `curl ... | bash`, `wget`, `powershell -enc`, `chmod 777`, `sudo`, `nc -e`, `bash -i`, and `/etc/passwd`.

## Audit Logs

Audit logs are written as JSONL to:

```text
gateway/logs/audit.jsonl
```

The audit logger stores masked prompts and hashed finding values. Raw detected secrets are not written to logs.
