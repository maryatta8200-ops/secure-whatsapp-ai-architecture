# Inbound receiver requirements

## Why an external receiver is mandatory

Twilio delivers inbound WhatsApp messages by making an HTTPS request to a
publicly reachable URL and expecting a fast `2xx` response. An Android
application cannot satisfy that contract:

- the app process is killed or suspended by the OS, especially when backgrounded;
- the device has no stable public address, and carrier NAT and IPv6-only
  networks make inbound connectivity impossible;
- Doze and app standby defer network work;
- a device that is offline, out of battery or switched off cannot acknowledge
  anything.

The app therefore **does not claim** to be a webhook endpoint. It integrates
with a receiver that is publicly reachable, authenticated, observable and
documented, and it displays that receiver's real state.

## Chosen architecture: Twilio Functions + Twilio Sync, behind a client seam

```
WhatsApp user
   │
   ▼
Twilio WhatsApp number
   │  webhook (X-Twilio-Signature, form encoded)
   ▼
Twilio Function (deployed by you, in your Twilio account)
   ├─ validates the Twilio signature
   ├─ writes the message into a Twilio Sync Map/List (durable queue)
   └─ returns 200 immediately  ← Twilio is acknowledged here, not later
   │
   ▼
Android app  ──authenticated pull──►  Twilio Function (list endpoint)
             ◄─── message batch ───
             ──acknowledge(ids)──►   removes them from the queue
```

The app talks to a `ReceiverClient` interface with four operations:

| Operation | Purpose |
| --- | --- |
| `health()` | Receiver reachability, queue depth, oldest message age. Drives the health states shown in the UI. |
| `pull(limit)` | Fetches at most `limit` pending messages. Bounded, so memory and CPU stay bounded. |
| `acknowledge(ids)` | Removes messages from the queue after durable local persistence succeeded. |
| `release(ids, delay)` | Returns a message to the queue for a later retry after a transient failure. |

Only the Twilio Functions + Sync implementation ships in v1. Because the app
depends on the interface, a different documented relay (for example a
Cloudflare Worker or a Supabase Edge Function implementing the same contract)
can be added later without redesigning the app.

## What you must deploy and configure

These are external prerequisites. The app blocks activation until they are
satisfied and validated, and never substitutes a simulated response for them.

1. A Twilio account with a WhatsApp-enabled sender (Twilio Sandbox for
   WhatsApp during development, an approved WhatsApp Business sender for
   production; templates and user consent are required for business-initiated
   messages).
2. A **Twilio Function** (Node.js) deployed in that account providing:
   - `POST /inbound` — the webhook target configured on the Twilio number.
     Verifies the Twilio signature, writes the message to Sync, returns `200`.
   - `GET /messages` — authenticated list of pending messages.
   - `POST /ack` — authenticated acknowledgement of processed message ids.
   - `GET /health` — queue depth, oldest pending age, receiver version.
3. A **Twilio Sync Service** with a Map or List used as the queue, plus a
   bounded TTL so abandoned messages do not accumulate forever.
4. A **shared secret or Twilio-issued token** used by the app to authenticate
   against the Function. The app stores it in an encrypted credential slot
   (milestone 3).
5. The **public HTTPS URLs** of those endpoints, entered in the app's receiver
   configuration screen.

The Function source ships with milestone 6 in `receiver/twilio-functions/`,
together with a deployment checklist and the exact environment variables it
needs.

## Guarantees, stated honestly

| Situation | What actually happens |
| --- | --- |
| App is offline or killed | Twilio is acknowledged by the Function; the message waits in Sync until the app pulls it. The message is not lost, and the app does **not** claim it was processed. |
| App pulls then dies before sending the reply | The message is still unacknowledged in Sync, so it is redelivered and reprocessed; the durable idempotency key prevents a duplicate AI call or a duplicate outbound message. |
| Receiver is unreachable | App health shows `DISCONNECTED` with the last successful check time. Retry uses `RetryPolicy.RECEIVER_PULL`. No inbound message is reported as processed. |
| Sync queue grows | `health()` reports queue depth and oldest age; the dashboard shows them so the backlog is visible rather than hidden. |
| Invalid signature or replay | Rejected by the Function (and re-validated by the app), audited, and never handed to an AI provider. |

## Outbound

Outbound does not need a public endpoint: the app calls the Twilio REST API
directly. Delivery is tracked through Twilio status callbacks, which are also
delivered to the receiver and pulled like any other inbound event.
