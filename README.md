# SecureWA Android app

SecureWA is a native Android client for the secure WhatsApp AI architecture in this repository. It has a real, user-controlled handoff flow and an optional server-side AI/WhatsApp Cloud API connector.

## APK features

- Scan messages locally for common email addresses, phone numbers, card numbers, and API-key patterns.
- Review the redacted handoff text before sharing it to WhatsApp or WhatsApp Business.
- Receive text shared from another Android app into the review surface without sending it automatically.
- Store only policy metadata in an AES-GCM vault protected by the Android Keystore.
- Configure an HTTPS gateway URL and bearer token; the token is encrypted on-device.
- Ask an AI provider through the gateway without putting an OpenAI, Gemini, or Anthropic key in the APK.
- Optionally send reviewed text through the WhatsApp Cloud API through the gateway, with an explicit confirmation step and E.164 recipient validation.

## Security posture

- The APK has `INTERNET` permission only for an explicitly configured HTTPS gateway. It rejects HTTP endpoints and does not call AI providers or Meta directly.
- Provider and Meta credentials belong on the gateway host. They are never bundled in the client and are not sent to WhatsApp from the APK.
- WhatsApp consumer/Business integration uses Android's user-mediated `ACTION_SEND` contract. SecureWA cannot read WhatsApp chats or send a message without the user completing the share flow.
- Android backup and device transfer are disabled in the manifest.
- Cleartext traffic is disabled.
- The client and reference gateway do not log request bodies.

The optional gateway is a reference implementation, not a hosted service. Configure and deploy it yourself before using AI or Cloud API actions.

## Build the debug APK

```bash
./gradlew assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. A CI-built copy is kept at `artifacts/SecureWA-debug.apk`.

Install on a connected device or emulator:

```bash
adb install -r artifacts/SecureWA-debug.apk
```

## Configure the real integrations

1. Deploy the reference gateway in [`gateway/`](gateway/) behind TLS.
2. Set `GATEWAY_TOKEN` and one provider key in `gateway/.env`; add Meta credentials only if Cloud API sending is needed.
3. In SecureWA, open **Privacy settings**, enter the gateway's HTTPS URL and the matching bearer token, and optionally add an E.164 Cloud API recipient.
4. Scan a message, review the redacted preview, then choose **Ask secure AI gateway**, **Share safe version to WhatsApp**, or **Send via WhatsApp Cloud API**.

See [`gateway/README.md`](gateway/README.md) for provider and Meta setup. No API credentials are included in this repository or APK.

To test the inbound share flow from another Android app:

1. Select text and tap **Share**.
2. Choose **SecureWA**.
3. Review the imported text, scan it, then choose whether to share it onward.
