# SecureWA gateway

This is a dependency-free Python reference gateway for the APK. It keeps AI-provider and Meta credentials off the Android device.

## Contract

The app is configured with an HTTPS base URL and an optional bearer token. It calls:

- `POST /v1/secure/complete` with `{ "message": "...", "client": "securewa-android", "version": "1.1" }`; returns `{ "text": "..." }`.
- `POST /v1/whatsapp/send` with `{ "to": "+923001234567", "text": "..." }`; returns `{ "text": "Message accepted by Meta" }`.
- `GET /health` for a basic health check.

The APK has no provider or Meta keys. The gateway validates its own bearer token, sends requests upstream, and does not log request bodies.

## Run

```bash
cp gateway/.env.example gateway/.env
# Edit .env. Load it with your process manager or export the variables.
set -a; . gateway/.env; set +a
python3 gateway/server.py
```

The reference server prints a warning and runs plain HTTP when no certificate is configured. That is for local inspection only. The APK intentionally rejects HTTP. For deployment, terminate TLS 1.2+ in a reverse proxy and forward to the process, or set `TLS_CERT_FILE` and `TLS_KEY_FILE`.

## AI provider setup

Set `AI_PROVIDER` and the matching secret:

- `openai`: `OPENAI_API_KEY`, optionally `OPENAI_MODEL` and `OPENAI_BASE_URL`.
- `gemini`: `GEMINI_API_KEY`, optionally `GEMINI_MODEL`.
- `anthropic`: `ANTHROPIC_API_KEY`, optionally `ANTHROPIC_MODEL`.

The gateway accepts only the already-reviewed text supplied by the app. Repeat PII and authorization checks here before any provider call in a production deployment.

## WhatsApp Cloud API setup

For programmatic sending, create/configure a Meta WhatsApp Business Platform app, verify the sending phone number, obtain a long-lived system-user access token with the required messaging permission, and set `WHATSAPP_PHONE_NUMBER_ID` and `WHATSAPP_ACCESS_TOKEN` on the gateway host. The recipient must be an allowed/opted-in number under Meta's messaging rules. Keep the token on the server; never add it to the APK or `local.properties`.

The APK still offers the normal user-mediated share flow, which works with the installed consumer or Business WhatsApp application without a Meta Cloud API account.
