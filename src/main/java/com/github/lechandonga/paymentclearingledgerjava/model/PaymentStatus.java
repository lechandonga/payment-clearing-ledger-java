package com.github.lechandonga.paymentclearingledgerjava.model;

/**
 * 单据状态机（派生状态，不持久化于单据字段，由各金额字段唯一确定）：
 * <pre>
 *   AUTHORIZED ──capture──┐
*        │                ├─► PARTIAL_CAPTURED ──capture──► CAPTURED
*        └──void──► VOIDED         │                     │
*                                  └──void(剩余授权)──► CAPTURED
*   CAPTURED / PARTIAL_CAPTURED ──refund──► PARTIAL_REFUNDED ──refund──► REFUNDED
*   (剩余授权额为 0 后即不能再 void；退款额不能超过已捕获额)
* </pre>
*/
public enum PaymentStatus {
    AUTHORIZED,
    PARTIAL_CAPTURED,
    CAPTURED,
    PARTIAL_REFUNDED,
    REFUNDED,
    VOIDED
}
