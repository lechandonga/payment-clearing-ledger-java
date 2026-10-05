package com.github.lechandonga.paymentclearingledgerjava.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 结算批次：按币种汇总一个清算周期内已捕获未结算的资金，一次性过账到商户账户。
 * 批次由幂等键唯一确定，重复触发返回同一批次，不会重复入账。
 */
public record SettlementBatch(String batchId, String currency, long totalAmount,
                              Map<String, Long> merchantAmounts, String journalEntryId,
                              Instant createdAt) {
}
