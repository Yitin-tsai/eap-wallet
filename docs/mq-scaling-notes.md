# MQ 水平擴充 - 時序性與 Super Stream 遷移筆記

> Wallet Outbox Relay 的完整問題背景、狀態機、publisher confirm、批次策略與 retry 設計，見 [`docs/outbox-relay-design.md`](./outbox-relay-design.md)。
> 訂單入口的 queue depth admission control、503 policy 與驗證結果，見 [`docs/mq-backpressure-design.md`](./mq-backpressure-design.md)。
> Order HTTP 到 Wallet Outbox 的 1,000 TPS 全鏈路測試與兩輪瓶頸分析，見 [`docs/order-wallet-e2e-load-test.md`](./order-wallet-e2e-load-test.md)。

> 2026-05-05 ~ 05-06 討論紀錄
> Current status: this note is historical. TPS-80 retired the legacy `order.matched` runtime bus, `wallet.orderMatched.queue`, `order.orderMatched.queue`, `MatchedOrderListener`, and `MatchEventListener`. Current matched-trade settlement uses `TradeExecutedEvent`, `OrderTradeAppliedEvent`, `WalletTradeSettledEvent`, and MatchEngine completion markers.

---

## 一、現況

- RabbitMQ Topic Exchange，每個 module 固定 queue name
- 水平擴充時同名 queue 多個 consumer，round-robin 分配，不會重複消費
- 目前沒有做 partition / 分片
- Wallet 使用 `@Version` 樂觀鎖防止並發寫入

### 大流量承載原則

EAP 的大流量問題不能只靠 RabbitMQ、DB 或 Redis 單點解決，而要把整條 pipeline 當成一個可回壓系統：

```
API/order -> RabbitMQ -> wallet DB -> RabbitMQ -> matchEngine Redis -> RabbitMQ -> order/wallet DB
```

核心原則：

- **MQ 負責削峰，不是無限 buffer**：queue backlog 短暫上升是正常削峰；若 publish rate 長期高於 consume rate，代表下游已過載。
- **DB 負責正確性，不用 cache 取代交易**：wallet 的扣款、鎖額、結算必須保留 DB transaction、idempotency key、unique constraint 與 optimistic lock。
- **Redis 負責撮合原子性，不是無限水平擴充**：同一 market 的 order book 需要 price-time priority，不能任意多 worker 同時改同一本 book。
- **API 層必須回壓**：當 queue depth、DB latency、Redis latency、redelivery rate 超過門檻時，要 per-user / per-market rate limit，必要時回 `429` 或 `503`。
- **公平性不是無限制收單**：交易系統的公平性是在可承載容量內，用一致規則接收、排序、處理，而不是讓內部 queue 無限制堆積。

面試口述：

> MQ 在我的架構中負責削峰與解耦，但我不把它當無限 buffer。大流量下我會看每條 queue 的 depth、consume rate、ack latency 和 redelivery rate。如果 publish rate 長期高於 consume rate，代表下游已經過載，API 層需要啟動 rate limit 或 backpressure，避免 queue 無限制膨脹。

### 各外部服務的壓力點

| 元件 | 主要瓶頸 | 正確處理方式 |
|------|----------|--------------|
| RabbitMQ | backlog、ack latency、redelivery rate | 監控 queue depth；prefetch；DLQ；超標時 API 回壓 |
| Wallet DB | transaction lock、hot wallet、connection pool | 短交易；consumer concurrency 對齊 DB pool；idempotency table；optimistic lock retry |
| Order DB / Audit | audit 寫入、查詢索引、同步阻塞 | per-order/per-correlation audit chain；必要時非同步化；避免全域序列化 |
| Redis order book | hot market ZSET、Lua script 時間、單執行緒 | marketId 分 key；同 market single writer；Lua 保持短小；必要時熱 market 獨立部署 |
| API Gateway / Order API | 無限制接單導致內部堆積 | per-user / per-market rate limit；依下游健康度回 `429` / `503` |

### Wallet DB 承載策略

Wallet 是資金正確性邊界，不能用「快取先扣錢」取代 DB transaction。

可做：

- 多個 wallet consumer pod 消費同一 queue，提高不同 user / 不同 wallet 的並行能力。
- `prefetch` 控制每個 consumer 手上的未 ack 訊息數，避免慢 pod 囤積。
- consumer concurrency 必須小於或等於 DB connection pool 可承受範圍，否則只是把瓶頸從 MQ 轉移到 DB。
- transaction 內只做必要 DB 操作，不做外部 IO。
- `@Version` optimistic lock + retry 解 concurrent wallet update。
- idempotency key + DB unique constraint 解 RabbitMQ duplicate delivery。
- 若 hot user / hot wallet 衝突變成常態，未來用 userId partition 或單 wallet 序列化處理。

面試口述：

> Wallet DB 是我系統裡不能犧牲正確性的瓶頸，所以我不會用 cache 取代 DB transaction。我的策略是讓 consumer 水平擴充，但用 optimistic lock + retry 處理低衝突並發；對重複投遞則用 idempotency table + unique constraint。真正高衝突的 hot wallet，未來會用 userId partition 或單 wallet 序列化處理。

### Match Engine / Redis 承載策略

Redis + Lua 是目前撮合的強點，但同一個熱門 market 仍會形成熱點。

可做：

- 不同 `marketId` 拆成不同 order book key，未來可分到不同 matchEngine / Redis shard。
- 同一 `marketId` 保留 single writer，避免破壞 price-time priority。
- Lua script 只做查詢、移除、寫入這類短操作，避免大量 scan。
- matchEngine 可以 batch consume，但同一 market apply 時仍依 `marketSequence` 順序處理。
- 熱門 market 可獨立部署 Redis / matchEngine instance。
- 若單一 market 流量超過連續撮合極限，應考慮產品規則調整，例如 frequent batch auction，而不是硬拆同一本 order book 破壞公平性。

面試口述：

> Redis order book 的擴充不能只靠多 pod，因為同一 market 的 price-time priority 需要單一順序來源。我的做法是先按 marketId 分 shard，不同 market 可以平行；同一熱門 market 則保留 single writer，靠 Lua 原子操作、短 script、batch ingest、前置限流撐住。如果真的超過單一 book 極限，就要調整產品規則，例如 batch auction，而不是破壞順序公平性。

### 下一步優先順序

> 更新：2026-06-16  
> `CreateOrderListener` 已補上 orderId-based idempotency table，並改成 insert-first claim。已用真實 PostgreSQL 跑過 24-thread concurrent duplicate delivery integration test，驗證同一筆 `OrderSubmittedEvent` 只會鎖資產一次、outbox 只產生一筆、idempotency record 只有一筆。  
> 另外已補 Prometheus / Grafana、wallet metrics、AMQP load generator，並完成 RabbitMQ 全鏈路壓測。

1. 已完成 MQ backlog / backpressure v1：快取 wallet queue depth / consumer count，warning threshold 只告警，hard / unavailable 回 `503`；既有 per-user rate limit 才回 `429`。
2. 已完成 Outbox Relay / Retry / Recovery v1：固定批次、持續 drain、publisher confirm、backoff、FAILED 隔離、metrics、alerts 與人工 requeue。
3. 下一步補 consumer concurrency 設計：wallet consumer 數、prefetch、DB connection pool 的關係。
4. 補 per-market rate limit：用 `marketId` 保護熱門市場。
5. 補 synthetic correctness stress test：大量混合 user / order / BUY / SELL / duplicate，直接驗證資料不變式。

### 2026-06-16 實測結果：Wallet AMQP Load Test

這次不是用 RabbitMQ Management HTTP API 發訊息，而是新增 Java AMQP load generator，直接 publish 到 `order.exchange` / `order.submitted`，讓事件走正式訊息路徑：

```text
WalletAmqpLoadGenerator
  -> RabbitMQ AMQP
  -> wallet.orderSubmitted.queue
  -> CreateOrderListener
  -> PostgreSQL transaction
  -> outbox
```

測試前另外加了 `loadtest` profile：

```yaml
spring.rabbitmq.listener.simple.concurrency: 4
spring.rabbitmq.listener.simple.max-concurrency: 8
spring.rabbitmq.listener.simple.prefetch: 20
```

目的不是把數字灌漂亮，而是避免每筆訂單 INFO log 變成壓測瓶頸，並讓 wallet listener 真的以多 consumer 方式處理訊息。

執行方式：

```bash
cd eap-wallet
GRADLE_USER_HOME=/Users/cfh00909120/Desktop/eap-workspace/.cache/gradle \
  ./gradlew --no-daemon bootRun --args='--spring.profiles.active=loadtest'

GRADLE_USER_HOME=/Users/cfh00909120/Desktop/eap-workspace/.cache/gradle \
  ./gradlew --no-daemon walletAmqpLoadTest \
  --args='--users 500 --events 10000 --tps 1000 --workers 64 --duplicate-ratio 0.10'

GRADLE_USER_HOME=/Users/cfh00909120/Desktop/eap-workspace/.cache/gradle \
  ./gradlew --no-daemon walletAmqpLoadTest \
  --args='--users 1000 --events 20000 --tps 3000 --workers 128 --duplicate-ratio 0.10'
```

第一輪結果：

```text
input events        = 10,000
target TPS          = 1,000
actual producer TPS = 998.25
publish failures    = 0
wallet consumed     = 10,000
duplicate skipped   = 1,039
optimistic retries  = 23
RabbitMQ backlog    = 0
outbox pending now  = 0
p95 processing      ~= 4.2 ms
p99 processing      ~= 8.9 ms
Hikari active max   = 6
Hikari pending max  = 0
```

第二輪結果：

```text
input events        = 20,000
target TPS          = 3,000
actual producer TPS = 2,751.65
publish failures    = 0
wallet consumed     = 20,000
duplicate skipped   = 2,013
optimistic retries  = 49
RabbitMQ backlog    = 0
outbox pending now  = 0
outbox pending peak = 10,768
p95 processing      ~= 5.1 ms
p99 processing      ~= 43.2 ms
Hikari active max   = 7
Hikari pending max  = 0
```

資料正確性檢查：

```sql
select count(*) as duplicate_order_ids
from (
  select order_id
  from wallet_service.order_submission_idempotency
  group by order_id
  having count(*) > 1
) t;
-- 0

select count(*) as negative_wallets
from wallet_service.wallets
where available_currency < 0
   or available_amount < 0
   or locked_currency < 0
   or locked_amount < 0;
-- 0
```

目前結論：

- Wallet 的 duplicate delivery correctness 是成立的：同一個 `orderId` 不會產生多筆 idempotency record，也不會造成資產負數。
- 多 consumer 下有實際打到 optimistic lock retry，代表測試不是單線程假測；目前 retry 能接住。
- DB pool 不是這兩輪瓶頸：Hikari pending 維持 0。
- RabbitMQ consumer 能追上：測後 queue backlog 回到 0。
- outbox relay 是下一個要觀察的點：高峰 pending 會累積到 10,768，但測後能清空。這表示交易邊界沒壞，但事件發布端需要獨立調校。

面試口述：

> 我不是只寫理論上的 idempotency，而是補了 AMQP load generator 讓事件真的走 RabbitMQ、Wallet listener、PostgreSQL transaction 和 outbox。實測 1,000 TPS 以及約 2,750 TPS producer throughput 下，wallet queue 都能清空，DB pool 沒有 pending，duplicate event 會在 idempotency claim 階段被擋掉。比較有價值的發現是 outbox 在高峰會短暫累積，這代表 wallet transaction 邊界是穩的，但 outbox relay 會是下一個要調校的瓶頸。

### 2026-06-22 實測結果：Outbox Relay v2

第一輪驗證 publisher confirm 時發現，只啟動 wallet 的壓測環境沒有啟動 order / matchEngine，因此 `order.confirmed` 與 `order.failed` 沒有下游 binding。舊 relay 呼叫 `convertAndSend()` 後就標記 `SENT`，會把 unroutable message 誤判為成功；v2 啟用 mandatory return + correlated publisher confirm 後，正確將這些事件保留為 `PENDING`。這表示先前的 outbox drain 數字只能證明資料列被標記完成，不能證明訊息已被 broker 路由。

為了隔離量測 relay，本輪在 `order.exchange` 建立臨時 load-test sink queue。第一版固定批次實測：

```text
input events        = 10,000
actual producer TPS = 998.80
outbox published    = 8,990
publish failures    = 0
outbox pending peak = 6,122
drain after publish ~= 20 seconds
```

原因是每輪 200 筆處理完成後固定等待 500ms，人為限制 relay 約 295 events/s。後續維持單一 poller 與每批 200 筆的記憶體上限，但 backlog 滿批時立即讀取下一批；只有未滿一批或失敗時才結束本輪並進入 fixed delay。若批次內有 publish failure，停止持續 drain，避免同一批失敗事件形成 tight retry loop。

調整後用相同參數重跑：

```text
input events        = 10,000
actual producer TPS = 998.90
wallet consumed     = 10,000
duplicate skipped   = 1,000
optimistic retries  = 11
outbox published    = 9,000
publish failures    = 0
outbox pending peak = 608 (0.5s sampling)
outbox pending now  = 0
sink queue messages = 19,000 (10,000 submitted + 9,000 outbox)
Hikari pending now  = 0
duplicate order IDs = 0
negative wallets    = 0
```

這一輪確認兩件事：publisher confirm 能阻止 unroutable message 被誤標成功；持續批次 drain 在不增加 worker 的前提下，將觀察到的 pending peak 從 6,122 降到 608。下一步應補失敗事件的持久化 retry metadata / backoff，而不是直接增加 relay worker。

### 2026-06-22 實作結果：Outbox Retry v1

Outbox 新增持久化重試生命週期：

```text
attempt_count
next_retry_at
last_error
updated_at
```

Poller 只查詢 `status=PENDING AND next_retry_at <= now` 的 due event。發布失敗時使用 exponential backoff，預設從 1 秒開始，上限 5 分鐘；累積 10 次失敗後改為 `FAILED`，不再自動重試。另外新增 `eap_wallet_outbox_retry_scheduled_total` counter 與 `eap_wallet_outbox_failed` gauge。

使用縮短測試參數（3 次上限，500ms / 1000ms backoff）對真實 RabbitMQ unroutable event 驗證：

```text
attempt 1 -> PENDING, next retry after 500ms
attempt 2 -> PENDING, next retry after 1000ms
attempt 3 -> FAILED, next_retry_at cleared
last_error -> AmqpException: Unroutable outbox event
```

Liquibase `wallet-013` 已成功套用於現有 PostgreSQL，wallet 全套測試通過。實際測試產生的 `FAILED` outbox record 已在驗證後刪除。

### 測試分層：Wallet 大流量驗證

這裡要先分清楚兩件事：

- **Correctness stress test**：大量或高併發事件下，資料不能錯。
- **Throughput load test**：系統每秒能處理多少事件、瓶頸在哪裡。

目前 24-thread 測試只證明第一層的 duplicate delivery correctness，不代表 production 大流量能力。實測要依序做下面幾步。

#### Step 1：把現有 24-thread integration test 固定成 correctness guard

目標：

- 保留 `CreateOrderListenerConcurrencyIT`。
- 確認它只在 `-Deap.integration.postgres=true` 時執行，避免一般 build 依賴本機 PostgreSQL。
- 驗證同一個 `orderId` 在並發重送下只會產生一次 wallet lock 和一次 outbox event。

驗證條件：

```text
wallet.availableCurrency = 90000
wallet.lockedCurrency    = 10000
outbox record count      = 1
idempotency record count = 1
```

#### Step 2：新增 synthetic correctness stress test

先不用 RabbitMQ，直接呼叫 listener，快速驗證資料正確性。

測試資料：

```text
orders: 1000 -> 5000 筆
users: 100 -> 500 個
duplicate ratio: 10%
order types: BUY / SELL 混合
thread pool: 32 -> 64
```

驗證重點：

```text
同一 orderId 只能有一筆 idempotency record
同一 orderId 只能有一筆 OrderConfirmedEvent 或 OrderFailedEvent
wallet available + locked 總額不可錯
availableCurrency / availableAmount 不可變負數
duplicate event 不可造成重複鎖資產
```

這一步仍然不是吞吐量壓測，重點是用大量案例找 correctness bug。

#### Step 3：改成 RabbitMQ 全鏈路驗證

接著不要直接呼叫 listener，改成讓事件真的進 RabbitMQ：

```text
test producer
  -> order.submitted routing key
  -> wallet.orderSubmitted.queue
  -> CreateOrderListener
  -> wallet DB
  -> outbox
```

測試目標：

- 驗證 RabbitMQ at-least-once、consumer concurrency、DB transaction、outbox 寫入放在一起時仍然正確。
- 故意送 duplicate `OrderSubmittedEvent`。
- 視情況調高 wallet listener concurrency / prefetch。

需要觀察：

```text
queue depth
publish rate
consume / ack rate
redelivery rate
DLQ count
outbox pending count
wallet DB connection usage
```

#### Step 4：做固定 TPS 的 load test

這一步才是在回答「流量很大時撐不撐得住」。

測試方式：

```text
固定 TPS：50 / 100 / 200 / 500 events/sec
每段持續：3 -> 5 分鐘
流量組成：不同 orderId、不同 userId、BUY / SELL 混合、少量 duplicate
```

觀察指標：

```text
OrderSubmittedEvent TPS
wallet lock latency p50 / p95 / p99
DB connection pool active / pending
PostgreSQL lock wait / slow query
RabbitMQ queue depth
RabbitMQ redelivery rate
outbox pending count
outbox publish latency
error rate
```

判斷標準：

- queue depth 短暫上升可以接受，長期上升代表 consumer 或 DB 跟不上。
- p95 / p99 latency 持續拉高代表進入瓶頸。
- DB pool 長期滿載代表 consumer concurrency 過高或 DB transaction 太慢。
- redelivery / DLQ 增加代表 listener 穩定性不足。

#### Step 5：根據瓶頸決定下一個工程動作

可能結果與對應處理：

| 現象 | 可能瓶頸 | 下一步 |
|------|----------|--------|
| queue depth 長期上升 | wallet consumer / DB 吞吐不足 | 調整 consumer concurrency、prefetch、DB pool |
| DB pool 長期滿載 | consumer 數量超過 DB 承載 | 降低 concurrency 或優化 transaction |
| optimistic lock retry 很高 | hot wallet / 同 user 高衝突 | 評估 userId partition 或單 wallet 序列化 |
| outbox pending 累積 | outbox relay 太慢 | 調整 relay batch / publish confirm / worker |
| duplicate skip 很高 | producer retry 或 redelivery 異常 | 追 RabbitMQ ack / retry / network |

目前已完成固定 TPS load test、Outbox Relay / Retry / Recovery v1，以及 Order API backpressure v1。下一步不是再盲目灌更高 TPS，而是建立 consumer concurrency、prefetch 與 DB connection pool 的容量模型，再用相同負載驗證調整結果。

### 現行架構拓撲

```
Producer                    Exchange + Binding              Queue                    Consumer
───────────────────────────────────────────────────────────────────────────────────────────────

eap-order                   order.exchange (Topic)
  PlaceBuyOrderService  --> routing: order.submitted ------> wallet.orderSubmitted.queue --> eap-wallet CreateOrderListener
  PlaceSellOrderService -->

eap-wallet (via Outbox)
  OrderConfirmed        --> routing: order.confirmed --+--> matchEngine.orderConfirmed.queue --> eap-matchEngine OrderConfirmedListener
                                                       +--> order.orderConfirmed.queue -------> eap-order OrderStatusUpdateListener

  OrderFailed           --> routing: order.failed --------> order.orderFailed.queue ----------> eap-order OrderStatusUpdateListener

eap-matchEngine
  MatchingEngineService --> routing: trade.executed ---+--> order.tradeExecuted.queue ---------> eap-order TradeExecutedListener
                                                       +--> wallet.tradeExecuted.queue --------> eap-wallet TradeExecutedListener
```

統計：1 個 Exchange、5 個 Routing Key、6 個 Queue、6 個 Binding

---

## 二、核心問題：時序性

同一用戶快速操作時，訊息可能被不同 pod 處理，導致 race condition：

```
用戶 A 快速下兩單（Buy #1, Buy #2）

Buy#1 -> wallet pod-1（鎖定餘額 1000）
Buy#2 -> wallet pod-2（同時讀到舊餘額，也鎖定 1000）
                        ^ Race condition!
```

### 現有保護：Optimistic Lock（@Version）

```
事務 A 讀 wallet（version=1）
事務 B 讀 wallet（version=1）
事務 A 寫入成功 -> version 變 2
事務 B 寫入失敗 -> OptimisticLockException -> 要自己 retry
```

- AuctionBidListener / AuctionSettlementListener 有做 retry（最多 3 次）
- Historical note: CreateOrderListener / retired MatchedOrderListener 當時沒有 retry，並發時會直接 exception。

### Prefetch（背壓機制）

不管選哪個方案都建議先加：

```yaml
# application.yml (所有 module)
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 10                      # 每個 consumer 手上最多 10 筆，避免囤積
        retry:
          enabled: true
          initial-interval: 1000
          max-attempts: 3
          multiplier: 2.0
        default-requeue-rejected: false   # 失敗進 DLQ，不重新入隊
```

prefetch 作用：consumer 手上最多拿 N 筆，處理完一筆才拿下一筆。
避免某個慢 pod 囤積大量訊息，也防止 crash 時大量訊息要重新投遞。

---

## 三、方案比較

| | Optimistic Lock（現在） | Pessimistic Lock | Super Stream |
|--|------------------------|-----------------|-------------|
| 改動量 | 已有 | 小（加 query method） | 中（換 protocol） |
| 並發保護 | exception + retry | DB 排隊等待 | 同 userId 天然有序，不需 lock |
| 時序保證 | 無 | 無（只防 lost update） | 同 userId 保證順序 |
| 高並發效能 | retry 浪費資源 | DB 壓力大 | partition 分散負載 |
| 事件回溯 | 不行 | 不行 | 可從任意 offset 重播 |

---

## 四、Super Stream 遷移方案（評估後決定暫不採用）

> **結論**：目前問題本質是 CDA listeners 缺少 optimistic lock retry，屬於應用層 bug，
> 不需要為此引入 Super Stream。以下保留方案設計作為未來參考。

### 架構對照

```
Producer                         Super Stream (partitioned)              Consumer Group
───────────────────────────────────────────────────────────────────────────────────────────────

eap-order                        order.submitted.stream [P0|P1|P2]
  PlaceBuyOrderService  -->        partition by: userId
  PlaceSellOrderService -->          +-- group: "wallet"  ----------------> eap-wallet CreateOrderListener


eap-wallet (via Outbox)          order.confirmed.stream [P0|P1|P2]
  OrderConfirmed        -->        partition by: userId
                                     +-- group: "matchEngine" -----------> eap-matchEngine OrderConfirmedListener
                                     +-- group: "order" -----------------> eap-order OrderStatusUpdateListener

                                 order.failed.stream [P0|P1|P2]
  OrderFailed           -->        partition by: userId
                                     +-- group: "order" -----------------> eap-order OrderStatusUpdateListener


eap-matchEngine                  trade.executed.stream [P0|P1|P2]
  MatchingEngineService -->        partition by: tradeId / marketId
                                     +-- group: "order" -----------------> eap-order TradeExecutedListener
                                     +-- group: "wallet" ----------------> eap-wallet TradeExecutedListener
```

統計：0 個 Exchange、0 個 Binding、4 個 Super Stream（各 3 partitions）、7 個 Consumer Group 訂閱

### 逐一對照表

| 事件 | 現在 | Super Stream |
|------|------|-------------|
| order.submitted | 1 routing key -> 1 queue -> 1 consumer | 1 stream x 3 partitions -> group:"wallet" |
| order.confirmed | 1 routing key -> 2 queues -> 2 consumers | 1 stream x 3 partitions -> group:"matchEngine" + group:"order" |
| order.failed | 1 routing key -> 1 queue -> 1 consumer | 1 stream x 3 partitions -> group:"order" |
| order.matched | 1 routing key -> 2 queues -> 2 consumers | 1 stream x 3 partitions -> group:"order" + group:"wallet" |

### 訊息流程比較（用戶 A 下買單）

```
現在：
  1. eap-order publish -> order.exchange -> routing:order.submitted
  2. RabbitMQ 查 binding -> 找到 wallet.orderSubmitted.queue
  3. 隨機派給某個 wallet pod（round-robin）
  4. 用戶 A 的第二筆可能去另一個 pod  <-- 時序問題

Super Stream：
  1. eap-order publish -> order.submitted.stream, routingKey=userId-A
  2. hash(userId-A) % 3 = 1 -> 進 partition-1
  3. partition-1 永遠由同一個 wallet pod 消費
  4. 用戶 A 的所有訂單都進 partition-1  <-- 天然有序
```

---

## 五、程式碼改動清單

### 5.1 Constants（取代 RabbitMQConstants）

```java
public class StreamConstants {
    // Stream names
    public static final String ORDER_SUBMITTED_STREAM = "order.submitted.stream";
    public static final String ORDER_CONFIRMED_STREAM = "order.confirmed.stream";
    public static final String ORDER_FAILED_STREAM    = "order.failed.stream";
    public static final String ORDER_MATCHED_STREAM   = "order.matched.stream";

    // Consumer group names
    public static final String GROUP_WALLET       = "wallet";
    public static final String GROUP_MATCH_ENGINE  = "matchEngine";
    public static final String GROUP_ORDER         = "order";

    // Partition count
    public static final int PARTITIONS = 3;
}
```

### 5.2 Config（新增 StreamConfig）

```java
@Configuration
public class StreamConfig {

    @Bean
    public SuperStream orderSubmittedStream() {
        return new SuperStream(ORDER_SUBMITTED_STREAM, PARTITIONS);
    }

    @Bean
    public SuperStream orderConfirmedStream() {
        return new SuperStream(ORDER_CONFIRMED_STREAM, PARTITIONS);
    }

    @Bean
    public SuperStream orderFailedStream() {
        return new SuperStream(ORDER_FAILED_STREAM, PARTITIONS);
    }

    @Bean
    public SuperStream orderMatchedStream() {
        return new SuperStream(ORDER_MATCHED_STREAM, PARTITIONS);
    }
}
```

### 5.3 Producer 改動

```java
// 現在
rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_SUBMITTED_KEY, event);

// Super Stream
rabbitStreamTemplate.convertAndSend(event, msg -> {
    msg.setHeader("userId", event.getUserId().toString());  // partition key
    return msg;
});
```

### 5.4 Consumer 改動

```java
// 現在
@RabbitListener(queues = WALLET_ORDER_SUBMITTED_QUEUE)
public void onOrderSubmitted(OrderSubmittedEvent event) { ... }

// Super Stream
@RabbitListener(
    queues = ORDER_SUBMITTED_STREAM,
    group = GROUP_WALLET,
    containerFactory = "streamListenerContainerFactory"
)
public void onOrderSubmitted(OrderSubmittedEvent event) { ... }
```

### 5.5 Docker Compose

```yaml
rabbitmq:
  image: rabbitmq:3-management-alpine
  ports:
    - "5672:5672"
    - "15672:15672"
    - "5552:5552"           # Stream protocol port
  environment:
    RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: "-rabbitmq_stream advertised_host localhost"
  command: >
    bash -c "rabbitmq-plugins enable rabbitmq_stream rabbitmq_stream_management &&
             rabbitmq-server"
```

### 5.6 Maven dependency

```xml
<dependency>
    <groupId>org.springframework.amqp</groupId>
    <artifactId>spring-rabbit-stream</artifactId>
</dependency>
```

---

## 六、遷移後可以拿掉什麼

| 現在有的 | Super Stream 後 |
|---------|----------------|
| order.exchange 宣告 | 不需要 |
| order.dlx / order.dlq | Stream 自帶 retention，不需要 DLQ |
| 6 個 queue 宣告 + binding | 改成 4 個 SuperStream bean |
| @Version optimistic lock retry | 可以拿掉（同 userId 順序保證，不會並發） |
| Outbox 的 routing key 欄位 | 改成 stream name |

### 不能拿掉的

| 保留 | 原因 |
|------|------|
| Outbox pattern | 仍需要 DB + MQ 一致性 |
| @Transactional | DB 操作本身仍需要原子性 |
| 冪等性檢查（matchId） | 網路重試仍可能重複 |

---

## 七、風險與注意事項

| 風險 | 說明 |
|------|------|
| Partition 數量固定 | 建了 3 個就是 3 個，之後改要 migrate |
| Pod 數 <= Partition 數 | 3 partitions 最多 3 個 pod 有效消費，第 4 個 pod 閒置 |
| 序列化 | Stream protocol 預設用 binary，要配 JSON converter |
| RabbitMQ 版本 | 需要 3.11+，確認 docker image 版本 |
| matched 事件 partition key | 買方和賣方是不同 userId，只能選一個當 key（見下方解法） |

### Historical: retired OrderMatchedEvent 的 partition key 問題

matched 事件同時影響買方和賣方錢包，但 partition key 只能選一個：

```
解法：publish 兩筆，各自用自己的 userId 當 partition key
  stream.send(matchedEvent, partitionKey = buyerId)
  stream.send(matchedEvent, partitionKey = sellerId)
  consumer 端用 matchId 做冪等，第二筆自動忽略同 matchId 的重複
```

---

## 八、相關程式碼位置

- Constants: `eap-common/.../constants/RabbitMQConstants.java`
- Wallet entity (@Version): `eap-wallet/.../domain/entity/WalletEntity.java`
- Wallet consumer: `eap-wallet/.../application/CreateOrderListener.java`
- Wallet matched: retired `MatchedOrderListener`; current path is `eap-wallet/.../application/TradeExecutedListener.java`
- MatchEngine producer: `eap-matchEngine/.../application/MatchingEngineService.java`
- Order publisher: `eap-order/.../application/PlaceBuyOrderService.java`
- Order listeners: `eap-order/.../application/OrderStatusUpdateListener.java`

---

## 九、已完成的修復（2026-05-06）

Winston（架構師）診斷後，確認問題是 CDA listeners 缺少 optimistic lock retry，不需要 Super Stream：

1. **CreateOrderListener** — 改用 TransactionTemplate + retry loop（max 3），與 AuctionBidListener 一致
2. **MatchedOrderListener** — retired in TPS-80; current settlement path is `TradeExecutedListener`
3. **所有 module application.yml** — 加入 `prefetch: 10` + `default-requeue-rejected: false`
4. **CreateOrderListenerTest** — 重寫，新增 optimistic lock retry 成功/失敗測試

## 十、Kafka 對比結論

不換 Kafka，原因：
- Kafka 基礎設施成本高（至少 3 node、大容量 SSD、專人維運）
- 學習專案規模不需要百萬級吞吐
- RabbitMQ Super Stream 已能提供 partition 順序保證（未來需要時可採用）
- 學會的概念（partition、consumer group、offset）直接可遷移到 Kafka
