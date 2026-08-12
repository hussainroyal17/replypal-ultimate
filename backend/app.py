"""ReplyPal Ultimate secure AI backend.

Deploy behind HTTPS. Keep OPENAI_API_KEY, OPENAI_BASE_URL and any authentication
configuration in the hosting environment; never place them in the Android app.
"""
import json
import os
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="ReplyPal Ultimate API", version="1.0.0")

STYLES = ["flirty", "friendly", "funny", "caring", "confident"]

class HistoryItem(BaseModel):
    body: str = Field(min_length=1, max_length=4000)
    direction: Literal["incoming", "outgoing"]
    timestamp: int | None = None

class ReplyRequest(BaseModel):
    contact_name: str = Field(default="Friend", max_length=120)
    latest_message: str = Field(min_length=1, max_length=4000)
    history: list[HistoryItem] = Field(default_factory=list, max_length=20)
    tone: str = Field(default="Casual", max_length=40)
    reply_length: str = Field(default="Short", max_length=30)
    emoji_level: str = Field(default="Normal", max_length=20)
    language: str = Field(default="Auto", max_length=20)

class ReplyResponse(BaseModel):
    suggestions: dict[str, list[str]]

SYSTEM_PROMPT = """You are ReplyPal Ultimate, a privacy-first conversation-writing assistant.
Return only valid JSON matching the provided schema. Create 3 concise, distinct, natural replies for each of flirty, friendly, funny, caring and confident. Do not be explicit or sexual. Do not facilitate deception, harassment, manipulation, impersonation, or non-consensual messaging. Match the supplied conversation language, including natural Hinglish, Hindi, Gujarati, or English. Never claim to send a message. Avoid robotic formality, repetition, and unsafe escalation."""

def client() -> OpenAI:
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise HTTPException(status_code=503, detail="AI service is not configured.")
    return OpenAI(api_key=key, base_url=os.environ.get("OPENAI_BASE_URL"))

@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

@app.post("/v1/replies", response_model=ReplyResponse)
def generate_replies(request: ReplyRequest) -> ReplyResponse:
    payload = request.model_dump()
    schema = {
        "type": "object",
        "properties": {
            "suggestions": {
                "type": "object",
                "properties": {style: {"type": "array", "items": {"type": "string"}, "minItems": 3, "maxItems": 3} for style in STYLES},
                "required": STYLES,
                "additionalProperties": False,
            }
        },
        "required": ["suggestions"],
        "additionalProperties": False,
    }
    try:
        response = client().chat.completions.create(
            model=os.environ.get("REPLYPAL_MODEL", "gpt-5-mini"),
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            response_format={"type": "json_schema", "json_schema": {"name": "replypal_suggestions", "strict": True, "schema": schema}},
            max_completion_tokens=1400,
        )
        raw = response.choices[0].message.content
        if not raw:
            raise ValueError("AI returned an empty response")
        result = json.loads(raw)
        return ReplyResponse.model_validate(result)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Reply generation is temporarily unavailable.") from exc
