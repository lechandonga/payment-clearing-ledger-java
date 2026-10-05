package com.github.lechandonga.paymentclearingledgerjava.model;

/**
 * 业务操作类型，同时也是幂等记录中的操作维度。
 */
public enum TxnType {
    AUTHORIZE,
    CAPTURE,
    VOID,
    REFUND,
    SETTLE,
    OPENING
}
