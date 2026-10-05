package com.github.lechandonga.paymentclearingledgerjava.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 幂等记录：键 = 操作类型 + 幂等键；保存首次请求参数指纹与首次响应。
 */
public record IdempotencyRecord(TxnType txnType,
                                String idempotencyKey,
                                String requestFingerprint,
                                JsonNode firstResponse,
                                int httpStatus) {
}
