package com.github.lechandonga.paymentclearingledgerjava.domain;

/**
 * 支付单状态机：
 * AUTHORIZED -> PARTIALLY_CAPTURED -> CAPTURED
 * AUTHORIZED/PARTIALLY_CAPTURED/CAPTURED -> VOIDED（释放剩余授权占用，终态，已捕获部分仍可退款）
 * 捕获后按退款进度 -> PARTIALLY_REFUNDED -> REFUNDED
 */
public enum PaymentStatus {
    AUTHORIZED,
    PARTIALLY_CAPTURED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED
}
