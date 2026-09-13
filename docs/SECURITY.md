# Security model

## Principles

1. Nothing that has not been verified is presented as verified.
2. Secrets never appear in a URL, a log, a notification, a crash report,
   an unencrypted database column, or an exported backup.
3. Every external operation is authenticated, bounded, retry-safe and
   observable.
4. Isolation is structural: numbers, agents, conversations, credentials and
   routing rules are addressed by stable ids and are never resolved by
   guessing, falling back or "best effort".

## Credentials

| Secret | Storage |
| --- | --- |
| Twilio Auth Token | Encrypted credential slot (milestone 3), Android Keystore-backed key |
| AI provider API keys | Encrypted credential slot |
| Receiver shared secret / token | Encrypted credential slot |
| Application passphrase | Never stored; used to derive the key that unlocks the vault |
| Release signing key | Never in the repository; supplied through `SECUREWA_*` environment variables in CI |

Credential slots are referenced by id (`credentialSlotId`). Several agents may
reference the same slot; that shares the ability to authenticate, and nothing
else — never prompts, memory, conversation history, permissions or private
configuration.

Environment variables used by the release build (never committed):

```
SECUREWA_KEYSTORE_PATH
SECUREWA_KEYSTORE_PASSWORD
SECUREWA_KEY_ALIAS
SECUREWA_KEY_PASSWORD
```

## Inbound authentication

Every inbound webhook is validated with Twilio's documented scheme:

```
signature = Base64( HMAC-SHA1( authToken, requestURL + sorted(keys+values) ) )
```

- comparison is constant time;
- a signature carries no expiry, so every accepted signature is recorded in a
  bounded `ReplayGuard` and a replay is rejected;
- a rejected request is audited and never reaches an AI provider;
- the same validation is applied to the receiver's own responses where
  applicable.

## Idempotency

| Artifact | Key |
| --- | --- |
| Inbound message | `sha256("inbound" | channel | destinationE164 | MessageSid)` |
| Provider request | `sha256("outbound" | provider | model | correlationId | attempt)` |
| Twilio send | `sha256("twilio-send" | correlationId | replyDigest)` |

Keys are computed from provider identifiers, not from content, so they are
stable across retries and cannot be poisoned by message text.

## Redaction

`Redactor` is applied to every string that can leave the processing boundary.
It masks phone numbers, removes known API key shapes, removes emails, removes
bare 32-character hex secrets, and drops any field whose name indicates a
secret (`Auth-Token`, `api_key`, `X-Twilio-Signature`, ...). Identifiers that
are needed for troubleshooting (Twilio `MessageSid`, correlation ids) are
deliberately preserved: a log that cannot be correlated is a log that will be
replaced by something unsafe.

## Data at rest

- `android:allowBackup="false"`, `fullBackupContent="false"` and
  `dataExtractionRules` excluding every domain, so no platform copy of the
  database can be taken.
- Database backups produced by the app (milestone 9) are encrypted and contain
  no recoverable secret without the key-management design described there.
- The release build disables cleartext traffic.

## Release build

- R8/obfuscation is off until milestone 9 ships a ruleset validated by tests;
  `gradle.properties` holds the switch and the milestone records when it flips.
- Release artifacts are unsigned unless a keystore is supplied through the
  environment. An unsigned release APK builds in CI to prove the variant
  compiles; it is explicitly not distributable and CI labels it as such.

## Reporting a vulnerability

Open a GitHub issue **without** including credentials, tokens, phone numbers or
message content. If the report requires sensitive detail, coordinate privately
with the repository owner first and redact everything with `Redactor` semantics.
