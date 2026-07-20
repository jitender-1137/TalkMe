# RabbitMQ Event-Driven Architecture — Design Proposal

**Status:** Draft for review — **not implemented**. Decide go/no-go before any code lands.
**Author:** generated 2026-06-19
**Scope:** Introduce RabbitMQ as the async event backbone for messaging, presence, calls, and notifications, enabling horizontal scale beyond a single instance.

---

## 1. Why (the actual problem this solves)

The system today uses Spring's **in-memory simple STOMP broker** (`WebSocketConfig.enableSimpleBroker`). That is correct and fast for a **single instance**, but it has one hard ceiling:

> A message published to `/topic/chat/{uuid}/messages` is only delivered to STOMP sessions **connected to the same JVM**.

So you cannot run two app instances behind a load balancer: if user A is on instance-1 and user B is on instance-2, B never receives A's message over WebSocket. This is the single blocker for the requirement's *"horizontal scalability / 1M+ concurrent users."*

RabbitMQ solves two distinct things — keep them separate in your decision:

| Concern | Solution | Required for HA? |
|---|---|---|
| **Cross-instance WebSocket fan-out** | STOMP relay to a broker (RabbitMQ w/ STOMP plugin) **OR** Redis Pub/Sub | **Yes** |
| **Durable, retryable async work** (delivery receipts, push, media processing, idempotent consumers, DLQ) | RabbitMQ AMQP queues + consumers | No, but high value |

> **Important honest note:** you already run **Redis**. For *only* the cross-instance fan-out problem, switching `enableSimpleBroker` → `enableStompBrokerRelay` against RabbitMQ, **or** adding a Redis Pub/Sub bridge, is far less invasive than a full AMQP rearchitecture. Full RabbitMQ AMQP is justified when you also want durable retries, DLQ, and decoupled consumers. Choose scope accordingly (see §9).

---

## 2. Topology overview

```
                    ┌────────────────────────────────────────────┐
   WebSocket/STOMP  │                 App instance N               │
   clients ───────▶ │  WebSocketController / MessageServiceImpl     │
                    │        │ publish (AMQP)        ▲ STOMP relay   │
                    └────────┼───────────────────────┼──────────────┘
                             ▼                        │
                    ┌──────────────────────────────────────────────┐
                    │                  RabbitMQ                      │
                    │  ── AMQP work plane ──   ── STOMP relay plane ─│
                    │  topic/direct exchanges  rabbitmq_stomp plugin │
                    │  + queues + DLX          /topic /queue relay   │
                    └──────────────────────────────────────────────┘
                             │ consumers                ▲ broadcast
                             ▼                           │
                    DB writes, push, receipts ── re-publish ── all instances' clients
```

Two planes inside one RabbitMQ:
- **Work plane (AMQP):** durable queues consumed by `@RabbitListener` workers. Reliability, retries, DLQ live here.
- **Relay plane (STOMP):** Spring's `StompBrokerRelay` points at RabbitMQ's STOMP plugin, so `/topic/**` and `/user/queue/**` fan out across **all** instances. This replaces `enableSimpleBroker`.

---

## 3. Exchanges

All durable. Use a topic exchange as the primary so routing keys carry intent.

| Exchange | Type | Routing key pattern | Purpose |
|---|---|---|---|
| `talkme.events` | topic | `message.send`, `message.delivery`, `message.read`, `user.presence`, `call.events`, `notification.events` | Primary domain events |
| `talkme.retry` | topic | mirrors above | Parking exchange for delayed retries (TTL → back to main) |
| `talkme.dlx` | topic | `#` | Dead-letter exchange; terminal failures land here |
| `media.processing` | direct | `media.process` | Heavier async media work (thumbnails, transcode) |

---

## 4. Queues (maps 1:1 to the requirement's list)

Every work queue is **durable**, holds **persistent** messages, uses **manual ack**, and dead-letters to `talkme.dlx`.

| Queue | Bound routing key | Consumer responsibility | DLQ |
|---|---|---|---|
| `q.message.send` | `message.send` | Persist message (idempotent), create receipts, re-publish broadcast | `dlq.message.send` |
| `q.message.delivery` | `message.delivery` | `bulkMarkAsDelivered`, emit status event | `dlq.message.delivery` |
| `q.message.read` | `message.read` | `bulkMarkAsRead`, emit status event | `dlq.message.read` |
| `q.message.retry` | `message.retry` | (see §6 — actually a TTL queue, not a worker) | — |
| `q.user.presence` | `user.presence` | Update `UserPresence` + Redis, broadcast presence | `dlq.user.presence` |
| `q.call.events` | `call.events` | Relay signaling events, track call state | `dlq.call.events` |
| `q.notification.events` | `notification.events` | `NotificationDispatchService` + Web Push | `dlq.notification.events` |
| `q.media.processing` | `media.process` | Thumbnails/transcode, then emit `message.send` | `dlq.media.processing` |

Per-queue args:
```
x-dead-letter-exchange: talkme.dlx
x-dead-letter-routing-key: <queue routing key>
x-message-ttl: <only on retry queues>
x-max-length / x-overflow: reject-publish-dlx   # backpressure guard
```

---

## 5. Reliability controls

- **Publisher confirms** — `spring.rabbitmq.publisher-confirm-type: correlated`; treat a message as accepted only after broker ack. On nack/timeout, the caller falls back to the existing synchronous DB write (no message loss during broker blips).
- **Consumer manual ack** — `acknowledge-mode: manual`; ack only after the DB transaction commits. Crash before ack ⇒ redelivery (at-least-once).
- **Durable + persistent** — survive broker restart.
- **Quorum queues** — use `x-queue-type: quorum` for the message queues (Raft-replicated, the modern HA default; classic mirrored queues are deprecated).
- **Prefetch** — `prefetch: 20` per consumer to bound in-flight work and apply backpressure.

---

## 6. Retry & RETRYING lifecycle (ties into requirement §1)

No poison-message infinite loops. Bounded retry with exponential backoff via TTL parking queues:

```
q.message.send  ──(consumer throws)──▶  nack/reject (no requeue)
        │ dead-letters to
        ▼
talkme.dlx ─routing─▶ q.message.retry.<n>   (x-message-ttl = 2^n seconds)
        │ TTL expires, dead-letters back to
        ▼
talkme.events ─routing─▶ q.message.send      (retry attempt n+1)
```

- Carry an `x-retry-count` header; increment per cycle.
- After **N=5** attempts → route to `dlq.message.send` (terminal) and mark the message **FAILED** in DB; broadcast `FAILED` status to the sender so the client shows the retry affordance.
- Map states to the client lifecycle: `SENT → RETRYING (in-flight retries) → DELIVERED/READ` or `→ FAILED`.

---

## 7. Idempotency (requirement: "idempotent consumers", "duplicate prevention")

At-least-once delivery means consumers **will** see duplicates. Make them idempotent:

1. Client already generates a stable `clientId` (UUID) per message — reuse it as the **idempotency key**.
2. Add a unique key on the message: `UNIQUE(chat_id, sender_id, client_id)` (new nullable `client_id` column on `messages`).
3. The send consumer does `INSERT ... ON CONFLICT (chat_id, sender_id, client_id) DO NOTHING RETURNING id` (same pattern already proven in `insertMissingReceipts`). A duplicate delivery is a no-op insert; the consumer still acks and re-broadcasts the existing row.
4. For non-DB events (presence/typing), idempotency is natural (last-write-wins on a single row).

This also closes the current gap where the server has **no** server-side dedup (it relies on client-side `clientId` dedup only).

---

## 8. How it maps onto the existing code (minimal-blast-radius integration)

The goal is to slot RabbitMQ **behind** the current API without rewriting controllers.

| Today | After |
|---|---|
| `MessageServiceImpl.sendMessage()` saves + `messagingTemplate.convertAndSend(...)` synchronously | `sendMessage()` validates, assigns `clientId`, then **publishes** `message.send` to `talkme.events`. A `@RabbitListener` worker does the persist + broadcast. Synchronous path kept as confirm-failure fallback. |
| `enableSimpleBroker("/topic","/queue")` | `enableStompBrokerRelay("/topic","/queue")` → RabbitMQ STOMP plugin (host/port/credentials). User-destination prefix unchanged. |
| Presence listener writes DB+Redis then `convertAndSend` | Publishes `user.presence`; consumer writes + broadcasts (now reaches all instances). |
| Web Push via `NotificationDispatchService` inline | Publishes `notification.events`; consumer dispatches (decoupled, retryable). |

Client (`websocket-provider.tsx`) needs **zero** changes — it still subscribes to the same `/topic` / `/user/queue` destinations; the relay makes them cluster-wide.

---

## 9. Rollout options (pick one — this is the decision)

**Option A — Fan-out only (smallest, ~1–2 days).** Keep all current logic; only swap `enableSimpleBroker` → `enableStompBrokerRelay` against RabbitMQ STOMP plugin (or add a Redis Pub/Sub bridge using existing Redis). Unblocks multi-instance. No AMQP work plane. *Recommended first step.*

**Option B — Fan-out + async work plane (~1–2 weeks).** Option A plus the AMQP exchanges/queues/DLQ/retry/idempotency for `message.send`, `presence`, `notification`. Full requirement coverage.

**Option C — Full event-sourced (weeks+).** All eight queues, media processing pipeline, call-event sourcing, distributed tracing across consumers. Only justified at real scale.

---

## 10. Infrastructure & ops

- **Local/dev:** add `rabbitmq:3.13-management` (with `rabbitmq_stomp` + `rabbitmq_management` plugins enabled) to `docker-compose`.
- **Config:** `spring-boot-starter-amqp` dependency; `spring.rabbitmq.*` for AMQP; relay host/port (`61613` STOMP) in `WebSocketConfig`.
- **Prod:** managed RabbitMQ (e.g. CloudAMQP) or a 3-node quorum cluster; TLS on AMQP + STOMP; per-vhost credentials.
- **Observability:** management UI, queue-depth + DLQ-depth alerts, consumer-ack-rate metrics, publisher-confirm failure rate.
- **Failure modes to test:** broker down (confirm-fallback to sync), consumer crash mid-process (redelivery), poison message (DLQ after N), queue overflow (reject-publish-dlx backpressure).

---

## 11. Risks

- STOMP relay changes the broker contract — needs an integration test of the full WS round-trip before prod.
- At-least-once + broadcast can double-deliver to clients; client already dedups by `clientId`, but verify presence/typing are idempotent on the client too.
- Two broadcast sources during migration (sync + consumer) can duplicate — feature-flag the cutover per event type.
- Operational surface grows (a new stateful dependency to run, monitor, secure, back up).

---

## 12. Recommendation

Start with **Option A** to unblock horizontal scale with minimal risk (it may even be done with your existing Redis instead of RabbitMQ). Adopt **Option B** for the durable/retry/DLQ guarantees in the requirement once Option A is stable in production. Defer **Option C** until single-cluster limits are actually hit.
