# SecureWA Android app

SecureWA is a native Android client surface for the secure WhatsApp AI architecture in this repository. It now has a real, user-mediated handoff flow rather than pretending to be a WhatsApp client:

- Scan a message locally for common email addresses, phone numbers, card numbers, and API-key patterns.
- Review the redacted handoff text before sharing it to WhatsApp or WhatsApp Business.
- Receive text shared from another app into the review surface without sending it automatically.
- Store only policy metadata in an AES-GCM vault protected by the Android Keystore.
- Manage redaction and encrypted-audit preferences, including clearing the local audit vault.

## Security posture

- The APK requests **no `INTERNET` permission**. It cannot upload messages or call an AI provider directly.
- Provider credentials are intentionally not included in the client. A production AI integration should route the approved, redacted request to a server-side HTTPS gateway that owns provider credentials.
- WhatsApp integration uses Android's user-mediated `ACTION_SEND` contract. SecureWA cannot read WhatsApp chats or send a message without the user completing the WhatsApp share flow.
- Android backup and device transfer are disabled in the manifest.
- Cleartext traffic is disabled.

The APK is a functional privacy and handoff client. It does not impersonate WhatsApp, read private chats, or claim to be an AI provider.

## Build the debug APK

```bash
./gradlew assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. A CI-built copy is kept at `artifacts/SecureWA-debug.apk`.

Install on a connected device or emulator:

```bash
adb install -r artifacts/SecureWA-debug.apk
```

To test the inbound share flow from another Android app:

1. Select text and tap **Share**.
2. Choose **SecureWA**.
3. Review the imported text, scan it, then choose whether to share it onward.
