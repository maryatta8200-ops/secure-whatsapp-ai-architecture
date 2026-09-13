# SecureWA Android app

SecureWA is a small, dependency-free Android demo for the secure WhatsApp AI architecture in this repository. It presents a privacy-first messaging surface with visible controls for encryption, PII checks, provider routing, and a local policy-check interaction.

## Security posture

- The APK requests **no `INTERNET` permission**. The sample interaction never leaves the device.
- Provider credentials are intentionally not included in the client. A production build would route approved requests through a server-side gateway.
- Android backup and device transfer are disabled in the manifest.
- Cleartext traffic is disabled.

The app is a UI and architecture demo; it does not connect to WhatsApp or an AI provider.

## Build the debug APK

```bash
./gradlew assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`.

Install on a connected device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
