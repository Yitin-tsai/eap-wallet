# EAP Wallet Service

`eap-wallet` is the asset-reservation and settlement boundary for EAP. It owns balances, locked assets, CDA reservation/settlement idempotency, TDA auction reservation/settlement, and the Wallet outbox.

## Current Flow

```text
OrderSubmittedEvent
  -> persist wallet_service.message_inbox, then ACK delivery
  -> lease worker claims the durable message
  -> claim order_id idempotently
  -> reserve BUY currency or SELL energy
  -> write Wallet state + OrderAssetReservationSucceeded/OrderFailed outbox + inbox APPLIED atomically

TradeExecutedEvent
  -> settle one trade inside one explicit DB transaction
  -> lock buyer/seller Wallet rows in stable UUID order
  -> validate postconditions
  -> persist one trade_settlements fact per trade_id
  -> manual ACK after commit

OrderCancellationResultEvent(CANCELLED)
  -> persist wallet_service.message_inbox, then ACK delivery
  -> lease worker claims the durable message
  -> lock the affected Wallet
  -> release only MatchEngine's atomically confirmed unmatched quantity
  -> store one cancellation application for duplicate-safe delivery
  -> write OrderAssetReservationReleasedEvent outbox + inbox APPLIED atomically
```

TDA uses a separate event flow:

```text
AuctionBidSubmittedEvent
  -> validate and reserve the bid's maximum asset exposure
  -> persist AuctionBidConfirmedEvent in the Wallet outbox

AuctionClearedEvent
  -> settle accepted auction quantities and release the unused reservation
```

The current bid listener has no durable event-idempotency claim, so sequential RabbitMQ redelivery can reserve the same bid more than once. A missing wallet or insufficient assets also returns without publishing a rejection result. Auction settlement is idempotent per auction/user/side, but participant failures are caught and processing continues, so an ACKed clearing event does not by itself prove every participant settled.

Wallet settlement does not report completion back to MatchEngine. Wallet's durable `trade_settlements` row and reconciled balances are the local proof; external verification compares them with MatchEngine and Order. Cancellation release is different: Wallet publishes `OrderAssetReservationReleasedEvent` so Order can distinguish MatchEngine's accepted cancellation (`CANCELLING`) from completion after the unmatched reservation is actually released (`CANCELLED`). The event exposes workflow identity and released quantity, not Wallet balances.

## Ownership

| Owns | Does not own |
| --- | --- |
| Available and locked currency/energy | Order lifecycle |
| Order reservation and rejection decisions | Matching or deal price selection |
| Cancellation application, asset release, and the durable release fact | Cancellation arbitration, order remainder, or Order's final status |
| Idempotent trade settlement | Cross-service completion state |
| Wallet integration-event outbox and retry state | AI or client orchestration |
| TDA bid reservation and auction-result settlement | Auction scheduling or clearing-price calculation |

## Reliability

- Reservation changes and confirmation/failure outbox rows share one local transaction.
- Reservation feasibility is part of the final conditional Wallet update, so concurrent distinct orders cannot reuse a stale balance check; the database also rejects negative available or locked balances.
- `OrderSubmittedEvent` and `OrderCancellationResultEvent` are persisted in `wallet_service.message_inbox` before business processing; listener return means durable intake, not business completion.
- The inbox reconciler uses `FOR UPDATE SKIP LOCKED`, a 30-second lease, owner fencing, exponential backoff, bounded jitter, and transient/permanent error classes.
- A cancellation release larger than the current locked asset is a permanent consistency conflict, not a prerequisite retry. The whole processing transaction rolls back and no release event is published.
- Reservation/rejection or cancellation release, business idempotency guards, result outbox, and inbox `APPLIED` share one local transaction. A lost lease rolls the whole processing transaction back.
- Settlement keeps an explicit transaction; the rejected autocommit experiment is not current behavior.
- Stable UUID lock ordering protects reversed buyer/seller concurrency from deadlocks.
- Unique keys and idempotency claims absorb RabbitMQ redelivery.
- Trade settlement and cancellation apply disjoint, idempotent asset deltas, so either delivery order converges to the same balances.
- Outbox publisher confirms, retry metadata, terminal `FAILED` state, and manual requeue make publication failure visible.
- TDA bid confirmation uses the Wallet outbox, but reservation redelivery, rejection feedback, and whole-auction convergence remain open gaps; the wider TDA path has not passed the CDA capacity and recovery contract.

Wallet trusts MatchEngine's immutable cancellation result for the exact unmatched quantity, as it already trusts `TradeExecuted` for matched quantity. Wallet still derives and guards the balance update locally; cancellation persistence is absent from normal order and trade write paths.

Locked currency and energy are fungible user-level pools. Wallet does not assign portions of the pool to individual orders; `order_id` is retained only as an idempotency identity. Order-level unmatched quantity remains a MatchEngine-owned fact, while Wallet enforces aggregate feasibility and non-negative balances.

The durable inbox does not solve a Wallet database outage before the inbox insert. In that window the listener cannot ACK and currently falls back to the short Spring Rabbit retry window and DLQ. Delayed transport retry or consumer pause, Saga age/timeout alerts, and a controlled DLQ recovery plane remain follow-up work. `TradeExecutedEvent` also still uses its dedicated idempotent settlement transaction rather than this shared message inbox.

## Run

```bash
./gradlew bootRun
```

Default port: `8081`; context path: `/eap-wallet`.

## Further Reading

- [Wallet outbox relay design](docs/outbox-relay-design.md)
- [MQ scaling notes](docs/mq-scaling-notes.md) - historical migration and scaling notes, not the current event contract
- [Wallet robustness report](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-05-wallet-settlement-robustness.md)
- [Wallet inbox and cancellation completion](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/wallet-inbox-and-cancellation-completion.zh-TW.md)
- [2026-09-03 current-version full-chain diagnostic](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-09-03-current-version-full-chain.md)
- [EAP system architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)
