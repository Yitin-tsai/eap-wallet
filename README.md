# EAP Wallet Service

`eap-wallet` is the asset reservation and settlement boundary for EAP.

It validates balances, locks funds, settles matched orders, and protects against overselling with optimistic locking and idempotency.

## Responsibilities

- Validate order-side asset availability
- Lock and unlock balances
- Perform final settlement after matching
- Publish downstream confirmations and failures

## What belongs here

- Wallet balance state
- Reservation / settlement logic
- Outbox publication
- Idempotency protection for repeated deliveries

## What does not belong here

- Order book matching
- AI orchestration
- UI / client-facing flow logic

## Main dependencies

- `eap-common` for shared DTOs and events
- RabbitMQ for async events
- PostgreSQL for wallet persistence

## Run

```bash
./gradlew :eap-wallet:bootRun
```

Default port: `8081`

## Notes

- This service is the main correctness boundary for overselling.
- Preserve idempotency when changing listeners or outbox logic.
