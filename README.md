# Payment Clearing Ledger (Java / Spring Boot)

本地可运行的支付受理与清结算服务：内存复式账本模拟资金流转，覆盖授权、捕获、撤销、退款全链路，
支持按币种汇总结算、写请求幂等和并发正确性。所有金额以最小货币单位（`long`，如分）记账，无浮点误差。

## 快速开始

```bash
mvn test          # 运行全部测试（状态机 / 幂等 / 并发 / 结算恰好一次）
mvn spring-boot:run
```

## 领域模型与资金流向

账户类型：`PAYER`（付款人）、`MERCHANT`（商户）、`PLATFORM_CLEARING`（平台清算过渡户，按币种一个）、
`EXTERNAL`（外部资金来源户，允许贷方余额，用于注入测试资金）。

| 操作 | 借贷分录（单币种、借贷平衡） |
| --- | --- |
| 授权 authorize | 无分录；PAYER 账户 `available -> held`（授权占用） |
| 捕获 capture | DR 平台清算户 / CR 付款人账户，同时释放对应占用 |
| 撤销 void | 无分录；释放剩余授权占用，单据进入终态 |
| 退款 refund | 未结算部分：DR 付款人 / CR 平台清算户；已结算部分：DR 付款人 / CR 商户账户 |
| 结算 settlement | DR 各商户账户（按商户汇总）/ CR 平台清算户（批次总额） |
| 存款 deposit | DR 目标账户 / CR 外部资金来源户（仅用于本地造数） |

## 状态机

```
AUTHORIZED --capture(部分)--> PARTIALLY_CAPTURED --capture(剩余)--> CAPTURED
CAPTURED/PARTIALLY_CAPTURED --refund(部分)--> PARTIALLY_REFUNDED --refund(全部)--> REFUNDED
AUTHORIZED/PARTIALLY_CAPTURED/CAPTURED --void--> VOIDED（终态；已捕获部分仍可退款）
```

不变量：`captured <= authorized`，`refunded <= captured`，`settled <= captured - refunded`。
非法流转（如对 VOIDED 单据捕获、超额捕获/退款）被拒绝并返回可区分错误码，状态与余额不被破坏。

## 错误码

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `PAYMENT_NOT_FOUND` / `ACCOUNT_NOT_FOUND` | 404 | 单据或账户不存在 |
| `INVALID_STATE_TRANSITION` | 409 | 当前状态不允许该操作（如对 VOIDED 捕获、重复撤销） |
| `CAPTURE_EXCEEDS_AUTHORIZED` | 409 | 捕获累计超过授权额 |
| `REFUND_EXCEEDS_CAPTURED` | 409 | 退款累计超过捕获额 |
| `INSUFFICIENT_FUNDS` | 409 | 可用余额不足 / 过账会导致负余额 |
| `CURRENCY_MISMATCH` | 400 | 账户与请求币种不一致，或分录内跨币种 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 写请求缺少 `Idempotency-Key` 头 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 同一幂等键携带不同参数，拒绝且不覆盖原结果 |
| `VALIDATION_FAILED` | 400 | 参数非法（金额非正、分录不平衡等） |

## 幂等规则

- 所有写接口（`POST`）必须携带 `Idempotency-Key` 请求头。
- 同键同参数重放：返回与首次完全一致的状态码与报文，并带 `X-Idempotent-Replay: true`，业务不重复执行。
- 同键不同参数：返回 `409 IDEMPOTENCY_KEY_CONFLICT`，首次结果被保留。
- 同键并发提交在键级别串行化，结果确定，不会出现互相覆盖。

## 清算、结算与对账规则

- 清算：捕获资金先进入按币种隔离的平台清算过渡户 `PLATFORM-CLEARING-{CCY}`，跨币种金额绝不直接相加。
- 结算：`POST /settlements` 按币种汇总所有“已捕获、未退款、未结算”的资金，按商户分组，
  生成一笔借贷平衡的分录（DR 各商户 / CR 清算户）并落批次。
- 恰好一次：批次与幂等键绑定，重复触发返回原批次；无新捕获时结算返回空批次（`totalAmount=0`），不重复入账。
- 对账：任一时刻可用以下恒等式核对——
  - 付款人账户：`balance - heldAmount = availableBalance >= 0`；
  - 每笔分录：单币种借贷相等（过账前强制校验，不平衡即整笔拒绝）；
  - 清算户余额 = 全部单据 `Σ(captured - refunded - settled)`；
  - 通过 `GET /accounts/{id}/entries` 可回放任一账户的全部分录。

## 并发正确性

- 同一支付单的操作按单据锁串行化：并发捕获/退款只会有一个确定性胜者，其余以专属错误码拒绝。
- 账本过账在全局锁内完成“校验平衡 → 校验币种 → 校验非负 → 原子应用”，失败路径整体回滚，不多扣不少扣。
- 结算按币种加锁，与单据操作通过单据锁协同，不会出现重复分录或结算遗漏。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/accounts` | 创建账户（幂等） |
| POST | `/accounts/{id}/deposit` | 注入测试资金（幂等） |
| GET | `/accounts/{id}` / `/accounts/{id}/entries` | 查询余额 / 分录 |
| POST | `/payments/authorize` | 授权（幂等） |
| POST | `/payments/{id}/capture` | 捕获，支持部分捕获（幂等） |
| POST | `/payments/{id}/void` | 撤销（幂等） |
| POST | `/payments/{id}/refund` | 退款，支持部分退款（幂等） |
| GET | `/payments/{id}` | 查询单据状态 |
| POST | `/settlements` | 按币种触发结算批次（幂等） |
| GET | `/settlements` / `/settlements/{batchId}` | 查询批次 |

示例：

```bash
KEY=$(uuidgen)
curl -X POST localhost:8080/accounts -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"accountId":"payer-1","type":"PAYER","currency":"CNY"}'
curl -X POST localhost:8080/accounts/payer-1/deposit -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"amount":100000,"currency":"CNY"}'
# 依次创建 MERCHANT 账户后：authorize -> capture -> refund -> settlements
```

## 本地验证

`mvn test` 运行 20 个用例，日志中每个用例输出 `[test] ... 通过` 及关键数值：

- `PaymentStateMachineTest`：状态机全链路、终态拒绝、超额捕获/退款、参数校验；
- `IdempotencyTest`：重放一致性、键冲突 409、缺键 400、捕获不重复过账；
- `ConcurrencyTest`：16 线程并发捕获/退款/授权/同键提交，验证恰好一次与无负余额；
- `SettlementTest`：按币种汇总、分录平衡、结算恰好一次、结算前后退款路径、跨币种隔离。
