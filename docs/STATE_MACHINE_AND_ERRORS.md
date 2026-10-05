# 状态机与错误码

## 1. 支付单据状态机

状态不是单独存储的字段，而是由 `authorizedAmount / capturedAmount / refundedAmount / voidedAmount`
四个最小货币单位金额**唯一派生**，因此不会出现“状态字段与金额不一致”：

| 派生条件 | 状态 |
| --- | --- |
| `captured=0 且 voided=authorized` | `VOIDED` |
| `refunded=captured 且 captured>0` | `REFUNDED` |
| `0 < refunded < captured` | `PARTIAL_REFUNDED` |
| `captured=authorized` | `CAPTURED` |
| `0 < captured < authorized`（剩余授权可再捕获或撤销） | `PARTIAL_CAPTURED` |
| 其它（`captured=0 且 voided=0`） | `AUTHORIZED` |

流转规则：

```
AUTHORIZED ──capture(≤剩余授权)──► PARTIAL_CAPTURED ──capture──► CAPTURED
     │                                   │                        │
     │                                   └──void(剩余授权)──┐      ├──refund──► PARTIAL_REFUNDED ──► REFUNDED
     └──void(全额/剩余)──► VOIDED                            ▼      │
                                                 (剩余授权为0)      └──refund
```

不变量（任何并发/失败组合下都成立）：

- `capturedAmount + voidedAmount ≤ authorizedAmount`
- `refundedAmount ≤ capturedAmount`
- `remainingAuth = authorized − captured − voided ≥ 0`
- `refundable = captured − refunded ≥ 0`

## 2. 错误码

错误响应统一形如：

```json
{
  "code": "CAPTURE_EXCEEDS_AUTH",
  "message": "捕获累计金额超过授权金额",
  "detail": "CAPTURE_EXCEEDS_AUTH: request=6000, remainingAuth=5000",
  "timestamp": "2026-10-05T08:00:00Z"
}
```

| 错误码 | HTTP 状态 | 含义与触发场景 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 缺少/空白字段、金额非正、期初余额为负、币种在系统内未开户 |
| `PAYMENT_NOT_FOUND` | 404 | 捕获/撤销/退款/查询的单据不存在 |
| `ACCOUNT_NOT_FOUND` | 404 | 资金账户不存在 |
| `PAYMENT_ALREADY_EXISTS` | 409 | 授权使用了已存在的支付单号（非幂等重放） |
| `ACCOUNT_ALREADY_EXISTS` | 409 | 开户使用了已存在的账户号 |
| `CURRENCY_MISMATCH` | 409 | 同一单据链路币种不一致，或授权币种与账户币种不同 |
| `ILLEGAL_STATE_VOIDED` | 409 | 对已全额撤销的单据做捕获或退款 |
| `CAPTURE_EXCEEDS_AUTH` | 409 | 本次捕获会使累计捕获额超过授权额（含剩余授权已为 0） |
| `VOID_EXCEEDS_REMAINING` | 409 | 本次撤销会超过剩余可撤销授权额 |
| `REFUND_EXCEEDS_CAPTURED` | 409 | 本次退款会使累计退款额超过已捕获额（未捕获先退款也归此码） |
| `INSUFFICIENT_FUNDS` | 409 | 授权时客户可用余额小于授权金额 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键已用于**参数不同**的请求；首次结果绝不被覆盖 |
| `SETTLEMENT_NOTHING_DUE` | 409 | 该币种当前没有待结算资金（上一批次已结清） |

注意：校验顺序保证了错误码的确定性。例如授权时“账户币种不符”优先于“币种未开户”，
“幂等键冲突”优先于一切业务校验——只要同一个 (操作类型, 幂等键) 已绑定了不同的请求参数，
无论业务当前是什么状态，都稳定返回 `IDEMPOTENCY_CONFLICT`。

## 3. 幂等语义

- 所有写接口都必须携带 `idempotencyKey`，缺失返回 `INVALID_REQUEST`。
- 幂等作用域是 **(操作类型, 幂等键)**：捕获和退款可以各自使用同一个键值而不冲突。
- 服务端对首次请求保存“请求参数指纹 + 首次响应（含首次 HTTP 状态）”。
- 同键同参重放：直接返回首次响应，**不再做任何业务动作、不再产生分录**。
- 同键异参：返回 `IDEMPOTENCY_CONFLICT`，并在 `detail` 中给出首次请求与本次请求指纹。
- 业务失败（4xx）不占用幂等键，纠正参数后可以用同一个键重试成功。
