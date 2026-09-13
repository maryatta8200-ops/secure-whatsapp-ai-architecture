#!/usr/bin/env python3
"""Minimal HTTPS-reverse-proxy-ready SecureWA gateway.

The Android client sends only reviewed text here. This process owns AI-provider
and Meta credentials, which are read from environment variables and never
returned to the client. Put this service behind a real TLS reverse proxy before
using the APK; the APK rejects non-HTTPS gateway URLs.
"""

from __future__ import annotations

import json
import os
import re
import ssl
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Tuple

MAX_BODY_BYTES = 64 * 1024
MAX_MESSAGE_CHARS = 12_000
E164 = re.compile(r"^\+[1-9]\d{7,14}$")


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def provider_request(url: str, headers: Dict[str, str], payload: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
    request = urllib.request.Request(
        url,
        data=json_bytes(payload),
        headers={"Content-Type": "application/json", "Accept": "application/json", **headers},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            body = response.read(1_000_000)
            return response.status, json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as error:
        # The caller gets only a safe status message; provider response bodies
        # are deliberately not logged or reflected to the Android client.
        try:
            json.loads(error.read(1_000_000).decode("utf-8"))
        except Exception:
            pass
        return error.code, {}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        return 599, {}


def complete_openai(message: str) -> str:
    key = env("OPENAI_API_KEY")
    if not key:
        raise GatewayError("OPENAI_API_KEY is not configured")
    base = env("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    status, data = provider_request(
        f"{base}/chat/completions",
        {"Authorization": f"Bearer {key}"},
        {
            "model": env("OPENAI_MODEL", "gpt-4o-mini"),
            "messages": [
                {"role": "system", "content": "Answer helpfully and do not request secrets."},
                {"role": "user", "content": message},
            ],
            "temperature": 0.2,
            "max_tokens": 1024,
        },
    )
    if status < 200 or status >= 300:
        raise GatewayError(f"AI provider rejected the request (HTTP {status})")
    try:
        text = data["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, TypeError, AttributeError):
        raise GatewayError("AI provider returned no text")
    if not text:
        raise GatewayError("AI provider returned an empty response")
    return text


def complete_gemini(message: str) -> str:
    key = env("GEMINI_API_KEY")
    if not key:
        raise GatewayError("GEMINI_API_KEY is not configured")
    model = env("GEMINI_MODEL", "gemini-2.0-flash")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    status, data = provider_request(
        url,
        {"x-goog-api-key": key},
        {"contents": [{"role": "user", "parts": [{"text": message}]}]},
    )
    if status < 200 or status >= 300:
        raise GatewayError(f"AI provider rejected the request (HTTP {status})")
    try:
        text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
    except (KeyError, IndexError, TypeError, AttributeError):
        raise GatewayError("AI provider returned no text")
    if not text:
        raise GatewayError("AI provider returned an empty response")
    return text


def complete_anthropic(message: str) -> str:
    key = env("ANTHROPIC_API_KEY")
    if not key:
        raise GatewayError("ANTHROPIC_API_KEY is not configured")
    status, data = provider_request(
        "https://api.anthropic.com/v1/messages",
        {"x-api-key": key, "anthropic-version": "2023-06-01"},
        {
            "model": env("ANTHROPIC_MODEL", "claude-3-5-haiku-latest"),
            "max_tokens": 1024,
            "messages": [{"role": "user", "content": message}],
        },
    )
    if status < 200 or status >= 300:
        raise GatewayError(f"AI provider rejected the request (HTTP {status})")
    try:
        text = data["content"][0]["text"].strip()
    except (KeyError, IndexError, TypeError, AttributeError):
        raise GatewayError("AI provider returned no text")
    if not text:
        raise GatewayError("AI provider returned an empty response")
    return text


def complete(message: str) -> str:
    provider = env("AI_PROVIDER", "openai").lower()
    if provider == "openai":
        return complete_openai(message)
    if provider == "gemini":
        return complete_gemini(message)
    if provider == "anthropic":
        return complete_anthropic(message)
    raise GatewayError("AI_PROVIDER must be openai, gemini, or anthropic")


def send_whatsapp(recipient: str, message: str) -> str:
    access_token = env("WHATSAPP_ACCESS_TOKEN")
    phone_number_id = env("WHATSAPP_PHONE_NUMBER_ID")
    if not access_token or not phone_number_id:
        raise GatewayError("WhatsApp Cloud API credentials are not configured")
    version = env("WHATSAPP_GRAPH_VERSION", "v20.0")
    status, data = provider_request(
        f"https://graph.facebook.com/{version}/{phone_number_id}/messages",
        {"Authorization": f"Bearer {access_token}"},
        {
            "messaging_product": "whatsapp",
            "to": recipient,
            "type": "text",
            "text": {"preview_url": False, "body": message},
        },
    )
    if status < 200 or status >= 300:
        raise GatewayError(f"WhatsApp Cloud API rejected the request (HTTP {status})")
    if not data.get("messages"):
        raise GatewayError("WhatsApp Cloud API returned no message id")
    return "Message accepted by Meta"


class GatewayError(Exception):
    pass


class Handler(BaseHTTPRequestHandler):
    server_version = "SecureWAGateway/1.0"

    def log_message(self, _format: str, *_args: Any) -> None:
        # Do not log paths, tokens, recipient numbers, or message content.
        return

    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_error(404)
            return
        self.reply(200, {"ok": True, "service": "securewa-gateway"})

    def do_POST(self) -> None:
        if not self.authorized():
            self.reply(401, {"error": "unauthorized"})
            return
        body = self.read_json()
        if body is None:
            return
        try:
            if self.path == "/v1/secure/complete":
                message = self.message(body)
                self.reply(200, {"text": complete(message)})
            elif self.path == "/v1/whatsapp/send":
                recipient = str(body.get("to", "")).strip()
                if not E164.fullmatch(recipient):
                    raise GatewayError("recipient must use E.164 format")
                self.reply(200, {"text": send_whatsapp(recipient, self.message(body))})
            else:
                self.reply(404, {"error": "not found"})
        except GatewayError as error:
            self.reply(502, {"error": str(error)})
        except Exception:
            self.reply(500, {"error": "gateway failed without exposing details"})

    def authorized(self) -> bool:
        expected = env("GATEWAY_TOKEN")
        received = self.headers.get("Authorization", "")
        return bool(expected) and received == f"Bearer {expected}"

    def message(self, body: Dict[str, Any]) -> str:
        message = body.get("message", body.get("text", ""))
        if not isinstance(message, str) or not message.strip():
            raise GatewayError("message is required")
        if len(message) > MAX_MESSAGE_CHARS:
            raise GatewayError("message is too long")
        return message.strip()

    def read_json(self) -> Dict[str, Any] | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY_BYTES:
            self.reply(413, {"error": "request body is too large or empty"})
            return None
        try:
            raw = self.rfile.read(length)
            value = json.loads(raw.decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError
            return value
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            self.reply(400, {"error": "invalid JSON"})
            return None

    def reply(self, status: int, payload: Dict[str, Any]) -> None:
        data = json_bytes(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main() -> None:
    host = env("GATEWAY_HOST", "127.0.0.1")
    port = int(env("GATEWAY_PORT", "8443"))
    server = ThreadingHTTPServer((host, port), Handler)

    cert = env("TLS_CERT_FILE")
    key = env("TLS_KEY_FILE")
    if cert and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certfile=cert, keyfile=key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    else:
        scheme = "http"
        print("WARNING: development HTTP only; use a TLS reverse proxy for the APK")

    print(f"SecureWA gateway listening on {scheme}://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
