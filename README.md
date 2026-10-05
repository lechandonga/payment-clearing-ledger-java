# payment-clearing-ledger-java

本地可运行的支付受理与清结算服务（Spring Boot 3 / Java 21，纯内存账本）。

- 授权 → 捕获（支持部分）→ 撤销（支持部分/剩余授权）→ 退款（支持部分）完整状态机
- 所有写请求带幂等键；重放返回首次结果；同键异参报 `IDEMPOTENCY_CONFLICT`
- 最小货币单位 `long` 记账，复式分录借贷平衡，跨币种隔离不相加
- 按币种的清算/结算批次，幂等 + 资源锁保证“恰好一次”入账
- 全局全序加锁，同单/同账户并发下不超额、不重账、无负余额

## 文档

- [状态机与错误码](docs/STATE_MACHINE_AND_ERRORS.md)
- [清算、结算与对账规则](docs/CLEARING_SETTLEMENT_RECONCILIATION.md)

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 开户（可带 `openingBalance` 期初注入） |
| GET | `/api/accounts/{accountId}` | 查询账户可用/占用余额 |
| POST | `/api/payments/authorizations` | 授权 |
| POST | `/api/payments/{paymentId}/captures` | 捕获（可部分） |
| POST | `/api/payments/{paymentId}/voids` | 撤销（全额或剩余授权） |
| POST | `/api/payments/{paymentId}/refunds` | 退款（可部分，不超过已捕获） |
| GET | `/api/payments/{paymentId}` | 查询单据状态与各金额 |
| GET | `/api/payments/{paymentId}/journals` | 单据分录 |
| POST | `/api/settlements` | 按币种触发结算批次 |
| GET | `/api/journals` | 全部凭证 |
| GET | `/api/reconciliation/trial-balance` | 按币种试算平衡表 |

金额单位均为最小货币单位整数。写请求示例：

```bash
curl -s -X POST localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"CUST-1","currency":"USD","openingBalance":100000}'

curl -s -X POST localhost:8080/api/payments/authorizations \
  -H 'Content-Type: application/json' \
  -d '{"paymentId":"P-1","accountId":"CUST-1","currency":"USD","amount":30000,"idempotencyKey":"k-auth-1"}'

curl -s -X POST localhost:8080/api/payments/P-1/captures \
  -H 'Content-Type: application/json' -d '{"amount":20000,"idempotencyKey":"k-cap-1"}'

curl -s -X POST localhost:8080/api/payments/P-1/refunds \
  -H 'Content-Type: application/json' -d '{"amount":5000,"idempotencyKey":"k-ref-1"}'

curl -s -X POST localhost:8080/api/settlements \
  -H 'Content-Type: application/json' \
  -d '{"currency":"USD","idempotencyKey":"k-set-1"}'
```

## 本地验证

前置：JDK 21、Maven 3.8+。

运行服务：

```bash
mvn spring-boot:run
```

运行全部测试（原 `ApplicationTests` 与新增 28 个业务用例）：

```bash
mvn clean test
```

测试日志会按业务板块打印覆盖内容与每条用例结果，形如：

```
========================================================================
【业务板块】StateMachineFlowTest
========================================================================
  [用例通过] 授权成功：状态 AUTHORIZED，可用余额转占用
  [用例通过] 超额捕获被拒绝：CAPTURE_EXCEEDS_AUTH，余额与占用不变
  ...
【StateMachineFlowTest】小计：通过 13，失败 0
```

测试分组：

| 测试类 | 覆盖业务 |
| --- | --- |
| `StateMachineFlowTest` | 状态机全流转、非法流转的可区分错误码、余额占用联动 |
| `IdempotencyReplayTest` | 同参重放一致、异参冲突、失败不占用幂等键 |
| `ConcurrentSubmissionTest` | 同键并发重放、不同键并发不超额捕获/退款/授权、无负余额 |
| `SettlementExactlyOnceTest` | 净额结算、并发批次恰好一次、跨币种隔离 |
| `LedgerReconciliationTest` | 每张凭证借贷平衡、试算表分币种平衡、账户余额可解释 |

测试为每个方法重置内存账本，互不串扰；所有状态仅保存在内存中，重启即清空。
