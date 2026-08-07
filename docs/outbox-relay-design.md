# Wallet Outbox Relay Design

> 最後更新：2026-06-22  
> 範圍：`eap-wallet` 的 transactional outbox 發布、RabbitMQ publisher confirm、批次 drain、失敗重試與監控。

> 現行狀態（2026-08-07）：本文件的可靠性原則仍有效，但實作已增加 JDBC 批次標記、可設定的發布並行度、批次確認，以及預設關閉的實驗性非同步 relay。標準與壓測預設仍使用單一同步 poller；只有顯式啟用非同步模式時，才以 `IN_FLIGHT`、`SKIP LOCKED` 與逾時重新取得處理權。Wallet trade settlement 不會寫入此 outbox，也不會發布完成回授事件。
>
> TDA 的 `AuctionBidConfirmedEvent` 也使用 Wallet outbox；但 Order 的競價出價事件與 MatchEngine 的競價生命週期事件目前直接發布，因此不可把 Wallet 的局部 outbox 保證延伸成整條 TDA 的可靠性宣稱。

### 現行發布狀態機

```text
PENDING
  -> broker ACK 且可路由 -> SENT
  -> 暫時失敗 -> PENDING + next_retry_at
  -> 超過重試上限 -> FAILED

實驗性非同步模式：
PENDING -> IN_FLIGHT -> SENT / PENDING / FAILED
              \-> lease timeout 後可重新取得
```

不論使用單筆相關確認或批次確認，只有 broker 確認成功且訊息可路由後才能標記 `SENT`。發布並行度、批次確認與非同步 relay 是執行設定，不是不同的業務語意；任何效能比較都必須記錄實際設定。

## 1. 要解決的問題

Wallet 在同一個資料庫 transaction 內完成資產異動與 outbox 寫入，避免「DB 已提交，但後續事件沒有被建立」的 dual-write 問題。但原始 Outbox Poller 還有三個缺口。

### 1.1 呼叫發送不等於 broker 已接收

原始流程在 `RabbitTemplate.convertAndSend()` 返回後立即把 outbox 標記成 `SENT`：

```text
read PENDING
  -> convertAndSend
  -> mark SENT
```

這只能證明應用程式呼叫過 RabbitTemplate，不能證明：

- RabbitMQ broker 已 ACK。
- Exchange 找得到符合 routing key 的 binding。
- 訊息確實進入至少一個 queue。

實測時只啟動 wallet，沒有啟動 order / matchEngine，下游 binding 不存在。舊版仍會將 `order.confirmed` 標成 `SENT`，造成資料庫看似成功、實際訊息遺失。

### 1.2 無上限查詢與固定等待限制吞吐

原始 poller 一次讀取所有 `PENDING`，backlog 大時會把全部 entity 載入記憶體。第一版批次化雖限制為每批 200 筆，但每批後固定等待 500ms，形成約 295 events/s 的人為吞吐上限。

### 1.3 失敗事件沒有生命週期

只將失敗事件保留成 `PENDING` 仍不完整。如果每 500ms 重試同一個永久失敗事件，會產生：

- 無限重試與大量重複 log。
- 持續打 RabbitMQ，放大故障。
- 無法知道已失敗幾次與最後錯誤。
- 無法區分暫時性失敗與 poison event。

## 2. 設計目標

這次改動採用「先建立單一 relay 的可靠基線，再考慮水平擴充」：

1. Broker ACK 且訊息成功路由後才能標記 `SENT`。
2. 每次資料庫查詢有固定上限，避免 backlog 造成記憶體風險。
3. 有 backlog 時持續 drain，空閒時才降低 polling 頻率。
4. 暫時性失敗使用持久化 exponential backoff。
5. 永久失敗在有限次數後隔離成 `FAILED`。
6. 所有關鍵狀態都可由 metrics 與資料庫檢查。

目前刻意維持單一 poller，不加入多 worker、distributed claim 或 `SKIP LOCKED`。現階段先把 correctness、failure lifecycle 與可觀測性做完整。

## 3. Outbox 資料模型

原有欄位：

```text
id
event_type
routing_key
payload
status
created_at
```

Liquibase `wallet-013` 新增：

| 欄位 | 用途 |
|------|------|
| `attempt_count` | 已失敗的發布次數 |
| `next_retry_at` | 下一次允許查詢與重試的時間 |
| `last_error` | 最近一次失敗原因，最多 1000 字元 |
| `updated_at` | 最近一次狀態更新時間 |

索引改為：

```sql
CREATE INDEX idx_outbox_pending_retry
ON wallet_service.outbox(next_retry_at, created_at)
WHERE status = 'PENDING';
```

索引與實際查詢條件一致，讓 relay 優先取得已到期且較舊的 pending event。

## 4. 狀態機

```text
                          broker ACK + routable
                 +---------------------------------> SENT
                 |
PENDING --publish+
                 |
                 +-- failure, attempts < max ------> PENDING
                 |                                   next_retry_at = now + backoff
                 |
                 +-- failure, attempts >= max -----> FAILED
                                                     no automatic retry
```

### `PENDING`

事件尚未成功發布，且可能正在等待下一次 retry。Poller 只選取：

```sql
status = 'PENDING'
AND next_retry_at <= now()
ORDER BY created_at
LIMIT :batchSize
```

### `SENT`

RabbitMQ publisher confirm 為 ACK，而且 mandatory return 沒有回報 unroutable，才會進入此狀態。

成功後：

```text
status        = SENT
next_retry_at = null
last_error    = null
updated_at    = now
```

### `FAILED`

累積失敗達到 `max-attempts` 後進入隔離狀態，不再由 poller 自動處理。事件與錯誤原因仍留在資料庫，供人工檢查或後續 replay 工具使用。

## 5. Publisher Confirm 流程

RabbitMQ 設定：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

每筆 outbox 使用自己的 `CorrelationData`：

```text
publish message
  -> wait for correlated confirm
  -> NACK / timeout: failure
  -> returned message: unroutable failure
  -> ACK and no return: mark SENT
```

Confirm timeout 預設 5 秒。這個設計修正了舊版「client 呼叫返回就假設成功」的錯誤成功判斷。

## 6. 批次與 drain 策略

預設每次只讀取 200 筆：

```yaml
eap:
  wallet:
    outbox-relay:
      batch-size: 200
      poll-interval-ms: 500
```

處理邏輯：

```text
read at most 200 due events
  -> publish sequentially and wait for confirm
  -> full batch and all succeeded: immediately read next batch
  -> partial batch: finish this scheduled run
  -> any failure: finish this scheduled run
  -> scheduler waits 500ms before the next run
```

因此：

- 單次查詢與記憶體使用有上限。
- Backlog 存在時不會在每批之間浪費 500ms。
- 空閒時每 500ms 查一次，不做高頻空查詢。
- 批次內發生失敗時停止持續 drain，避免同一批失敗資料形成 tight retry loop。

## 7. Retry 與 Backoff

預設設定：

```yaml
eap:
  wallet:
    outbox-relay:
      max-attempts: 10
      initial-backoff-ms: 1000
      max-backoff-ms: 300000
```

退避公式：

```text
delay = min(initialBackoff × 2^(attemptCount - 1), maxBackoff)
```

預設序列大致為：

```text
1s -> 2s -> 4s -> 8s -> 16s -> 32s -> 64s -> 128s -> 256s -> FAILED
```

每次失敗會更新：

```text
attempt_count += 1
last_error     = exception type + message
updated_at     = now
next_retry_at  = now + backoff
```

達到第 10 次時改成 `FAILED` 並清除 `next_retry_at`。

Backoff 的目的不是提高成功率，而是讓 RabbitMQ、網路或 routing topology 故障時，不會因立即重試造成額外壓力。

## 8. Metrics

由 `WalletMetrics` 註冊並透過 Actuator `/actuator/prometheus` 暴露：

| Metric | 類型 | 意義 |
|--------|------|------|
| `eap_wallet_outbox_pending` | Gauge | 目前 PENDING 數量 |
| `eap_wallet_outbox_oldest_pending_age_seconds` | Gauge | 最舊 pending event 等待時間 |
| `eap_wallet_outbox_published_total` | Counter | 收到 broker ACK 且成功路由的數量 |
| `eap_wallet_outbox_publish_failed_total` | Counter | NACK、timeout、unroutable 或其他發布失敗次數 |
| `eap_wallet_outbox_publish_duration_seconds` | Timer | 發布並等待 confirm 的耗時 |
| `eap_wallet_outbox_retry_scheduled_total` | Counter | 已排入 backoff retry 的次數 |
| `eap_wallet_outbox_requeued_total` | Counter | 經人工確認後重新排入 PENDING 的數量 |
| `eap_wallet_outbox_failed` | Gauge | 已隔離成 FAILED 的事件數 |

建議後續告警：

- `outbox_failed > 0`
- oldest pending age 持續超過服務可接受時間
- pending 持續增加而沒有下降
- publish failure rate 突然增加

## 9. 驗證結果

### 9.1 吞吐調整

相同的 10,000 events / 1,000 target TPS 測試：

| 項目 | 固定批次等待 | 持續批次 drain |
|------|--------------|----------------|
| Producer TPS | 998.80 | 998.90 |
| Pending peak | 6,122 | 608 |
| Pending final | 0 | 0 |
| Publish failures | 0 | 0 |

最終正確性結果：

```text
wallet consumed     = 10,000
duplicate skipped   = 1,000
outbox published    = 9,000
duplicate order IDs = 0
negative wallets    = 0
Hikari pending      = 0
```

### 9.2 真實失敗生命週期

使用沒有下游 binding 的真實 RabbitMQ routing，並將測試參數縮短為 3 次上限、500ms / 1000ms backoff：

```text
attempt 1 -> PENDING, retry after 500ms
attempt 2 -> PENDING, retry after 1000ms
attempt 3 -> FAILED
last_error -> AmqpException: Unroutable outbox event
```

驗證完成後已刪除該筆測試資料。

## 10. 一致性語意與限制

這個設計提供的是 at-least-once publishing，不是 exactly-once。

若 RabbitMQ 已 ACK，但應用程式在更新 `SENT` 前當機，該事件重啟後仍可能再次發布。因此下游 consumer 必須使用 event ID、order ID 或業務唯一鍵維持冪等。

目前限制：

- 單一 poller，不支援多 pod 同時 claim outbox。
- 沒有 `PROCESSING` / lease 狀態。
- 沒有 `FOR UPDATE SKIP LOCKED` 或其他 distributed claim。
- Recovery endpoint 沒有身份驗證，預設關閉；production 必須放在受保護的管理網路或補上正式授權。
- Requeue 操作目前以 application log 與 metric 留痕，尚無獨立的 operator audit table。
- Prometheus rules 尚未連接 Alertmanager，因此不會主動發送外部通知。

如果未來要多 worker 或多 pod，必須先加入安全 claim 機制，否則不同 relay 可能同時發布同一筆 outbox。現階段不以增加 worker 掩蓋設計缺口。

## 11. 設計理念摘要

這次改善遵循以下原則：

1. **Broker acknowledgement over optimistic assumption**：只有 broker 明確確認後才宣告成功。
2. **Bounded work**：每次資料庫查詢有上限，但 backlog 存在時持續工作。
3. **Failure is state**：失敗次數、下次重試與錯誤原因必須持久化，不能只存在 log。
4. **Retry with restraint**：重試需要 backoff 與上限，避免故障放大。
5. **At-least-once requires idempotency**：publisher confirm 不會消除重複發布，下游冪等仍是必要條件。
6. **Correctness before horizontal scaling**：先讓單一 relay 的成功與失敗語意完整，再談 worker 與多 pod。

## 12. Grafana Dashboard 與 Prometheus Alerts

### Dashboard panels

Dashboard：`EAP Wallet Observability`  
位置：`observability/grafana/dashboards/eap-wallet.json`

Outbox 相關 panels：

| Panel | 查詢目的 |
|-------|----------|
| Wallet Outbox Pending | 觀察 backlog 數量與是否能回落 |
| Outbox Oldest Pending Age | 判斷是否有事件長時間卡住 |
| Outbox Publish Outcomes | 比較每秒成功與失敗發布量 |
| Outbox Publish Confirm Latency | 顯示等待 broker confirm 的 p95 / p99 |
| Outbox Retry and Recovery Rate | 觀察 backoff retry 與人工 requeue 速率 |
| Outbox Permanently Failed | 以紅色 stat 顯示 `FAILED` 數量 |

### Alert rules

規則檔：`observability/prometheus/rules/eap-wallet-outbox.yml`

| Alert | 條件 | 等待時間 | Severity | 意義 |
|-------|------|----------|----------|------|
| `WalletOutboxFailedEvents` | `outbox_failed > 0` | 1m | critical | 已有事件耗盡 retry，需人工處理 |
| `WalletOutboxOldestPendingTooOld` | oldest pending age > 30s | 2m | warning | Relay 或 RabbitMQ 可能無法正常 drain |
| `WalletOutboxPendingGrowing` | pending 五分鐘趨勢 > 1 event/s | 5m | warning | 發布速度長期低於事件產生速度 |
| `WalletOutboxPublishFailures` | 五分鐘內出現發布失敗 | 1m | warning | RabbitMQ、網路或 routing topology 異常 |

Prometheus 使用 5 秒 evaluation interval 載入規則。`docker-compose.observability.yml` 將 rules directory mount 到 `/etc/prometheus/rules`。

### 啟動與檢查

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  up -d prometheus grafana
```

入口：

- Grafana：`http://localhost:3000/d/eap-wallet/eap-wallet-observability`
- Prometheus alerts：`http://localhost:9090/alerts`
- Prometheus rules：`http://localhost:9090/rules`

設定驗證：

```bash
docker run --rm --entrypoint promtool \
  -v "$PWD/observability/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v2.55.1 \
  check config /etc/prometheus/prometheus.yml

docker run --rm --entrypoint promtool \
  -v "$PWD/observability/prometheus/rules:/rules:ro" \
  prom/prometheus:v2.55.1 \
  check rules /rules/eap-wallet-outbox.yml
```

2026-06-22 驗證結果：Prometheus config 有效、4 條 rules 載入成功、Grafana dashboard 成功 provision 11 個 panels。

### Failure drill

建議使用非正式環境執行：

1. 啟動 wallet、Prometheus 與 Grafana。
2. 暫時移除 `order.confirmed` 的測試 binding，或將事件送往沒有 binding 的 routing key。
3. 確認 publish failure 與 retry metrics 增加。
4. 等待 retry 上限，確認 `FAILED` stat 與 `WalletOutboxFailedEvents` 進入 firing。
5. 恢復 binding。
6. 依 runbook 將確認可重送的 `FAILED` event 重設為 `PENDING`。

目前只有 Prometheus alert evaluation，尚未配置 Alertmanager 或 Grafana notification contact point。因此告警可在 Prometheus UI 看到 pending / firing 狀態，但不會主動發送 Slack、Email 或 PagerDuty 通知。

## 13. FAILED Event Recovery Runbook

### 設計目的

`FAILED` 代表事件已耗盡自動重試，不應由系統無條件繼續發送。人工 recovery 必須先修復根因，例如：

- 下游 queue / binding 不存在。
- Exchange 或 routing key 設定錯誤。
- RabbitMQ 權限或網路異常。
- Payload 或 event type 無法反序列化。

確認根因已排除且事件可安全重送後，才能將它重新排入 `PENDING`。

### 安全邊界

Recovery controller 預設不建立：

```yaml
eap:
  wallet:
    outbox-admin:
      enabled: false
```

只在本機或受保護的管理環境暫時啟用：

```bash
./gradlew bootRun --args='--eap.wallet.outbox-admin.enabled=true'
```

目前 endpoint 沒有 authentication / authorization，不可直接暴露至公開或一般服務網路。Production 化時應加入管理者身份、操作原因與 audit log。

### 查看 FAILED events

```bash
curl 'http://localhost:8081/eap-wallet/internal/outbox/failed?limit=50'
```

回應不包含完整 payload，避免在一般操作畫面暴露事件內容；提供：

```text
id
eventType
routingKey
status
attemptCount
lastError
createdAt
updatedAt
nextRetryAt
```

`limit` 最小為 1、最大為 100。

### 人工 requeue

```bash
curl -X POST \
  'http://localhost:8081/eap-wallet/internal/outbox/{id}/requeue'
```

只接受目前狀態為 `FAILED` 的事件：

- 找不到事件：HTTP `404`
- 事件不是 `FAILED`：HTTP `409`
- Requeue 成功：HTTP `200`

成功後更新：

```text
status        = PENDING
attempt_count = 0
next_retry_at = now
last_error    = null
updated_at    = now
```

Transaction commit 後 poller 才能看到該筆事件。重新發布仍採 publisher confirm、retry 與 max-attempts 規則。

### 重送風險

Publisher confirm timeout 是 ambiguous failure：broker 可能已收到事件，但應用程式沒有及時收到 ACK。因此即使狀態最後成為 `FAILED`，人工重送仍可能造成 duplicate delivery。Recovery 操作不能取代 consumer idempotency。

### 驗證結果

2026-06-22 使用本機 PostgreSQL 與條件式啟用的 controller 驗證：

```text
FAILED event id       = 62482
list failed           = success
requeue HTTP status   = 200
new status            = PENDING
attempt_count         = 0
last_error            = null
outbox_requeued_total = 1
```

測試資料已於驗證後刪除，wallet 程序已停止。

## 14. Pipelined Publisher Confirms 與 Bulk State Update

2026-06-24 的 Order → Wallet 全鏈路測試顯示，逐筆 `publish -> wait confirm -> save SENT` 會讓 RabbitMQ round trip 與資料庫 transaction 完全序列化。即使 batch query 一次讀 200 筆，實際發布仍是逐筆阻塞。

Relay 改為：

1. 依 `created_at` 順序讀取最多 200 筆 PENDING。
2. 依相同順序 publish 全批，每筆保留獨立 `CorrelationData`。
3. 全批共用一個 confirm deadline，再逐一檢查 ACK / NACK / returned message。
4. 成功項目用一條 conditional bulk UPDATE 從 PENDING 改成 SENT。
5. 失敗項目仍逐筆更新 attempt count、last error 與 next retry time。

這是 I/O pipelining，不是多 worker：publish 順序仍固定，記憶體上限仍是 batch size，也沒有讓多個 poller 同時競爭同一批資料。

Crash boundary 仍符合 at-least-once：若 RabbitMQ 已 ACK，但 process 在 bulk UPDATE 前終止，資料列仍為 PENDING，重啟後會再次發布。這可能造成 duplicate delivery，但不會造成 event loss，因此下游 idempotency 仍不可移除。

定向測試額外證明，同批第二筆已 publish 後才開始等待第一筆 confirm；NACK、max attempts、continuous drain 與 retry 測試也全部保留。
