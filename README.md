# EAP Wallet Service

`eap-wallet` is the asset-reservation and settlement boundary for EAP. It owns balances, locked assets, CDA reservation/settlement idempotency, TDA auction reservation/settlement, and the Wallet outbox.

## Current Flow

```text
OrderSubmittedEvent
  -> claim order_id idempotently
  -> reserve BUY currency or SELL energy
  -> write Wallet state + OrderConfirmed/OrderFailed outbox atomically

TradeExecutedEvent
  -> settle one trade inside one explicit DB transaction
  -> lock buyer/seller Wallet rows in stable UUID order
  -> validate postconditions
  -> persist one trade_settlements fact per trade_id
  -> manual ACK after commit
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

Wallet settlement does not report completion back to MatchEngine. Wallet's durable `trade_settlements` row and reconciled balances are the local proof; external verification compares them with MatchEngine and Order.

## Ownership

| Owns | Does not own |
| --- | --- |
| Available and locked currency/energy | Order lifecycle |
| Order reservation and rejection decisions | Matching or deal price selection |
| Idempotent trade settlement | Cross-service completion state |
| Wallet integration-event outbox and retry state | AI or client orchestration |
| TDA bid reservation and auction-result settlement | Auction scheduling or clearing-price calculation |

## Reliability

- Reservation changes and confirmation/failure outbox rows share one local transaction.
- Settlement keeps an explicit transaction; the rejected autocommit experiment is not current behavior.
- Stable UUID lock ordering protects reversed buyer/seller concurrency from deadlocks.
- Unique keys and idempotency claims absorb RabbitMQ redelivery.
- Outbox publisher confirms, retry metadata, terminal `FAILED` state, and manual requeue make publication failure visible.
- TDA bid confirmation uses the Wallet outbox, but reservation redelivery, rejection feedback, and whole-auction convergence remain open gaps; the wider TDA path has not passed the CDA capacity and recovery contract.

## Run

```bash
./gradlew bootRun
```

Default port: `8081`; context path: `/eap-wallet`.

## Further Reading

- [Wallet outbox relay design](docs/outbox-relay-design.md)
- [MQ scaling notes](docs/mq-scaling-notes.md) - historical migration and scaling notes, not the current event contract
- [Wallet robustness report](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-08-05-wallet-settlement-robustness.md)
- [EAP system architecture](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/architecture.md)
