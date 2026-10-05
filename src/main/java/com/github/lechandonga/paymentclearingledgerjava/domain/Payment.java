package com.github.lechandonga.paymentclearingledgerjava.domain;

import java.time.Instant;

/**
 * 支付单。金额均为最小货币单位（long），同一单据内币种唯一。
 * 不变量：0 <= capturedAmount <= authorizedAmount；0 <= refundedAmount <= capturedAmount；
 * 0 <= settledAmount <= capturedAmount - refundedAmount 的已清算部分。
 */
public class Payment {

    private final String paymentId;
    private final String payerAccountId;
    private final String merchantAccountId;
    private final String currency;
    private final long authorizedAmount;
    private long capturedAmount;
    private long refundedAmount;
    private long settledAmount;
    private boolean voided;
    private final Instant createdAt = Instant.now();

    public Payment(String paymentId, String payerAccountId, String merchantAccountId,
                   String currency, long authorizedAmount) {
        this.paymentId = paymentId;
        this.payerAccountId = payerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.currency = currency;
        this.authorizedAmount = authorizedAmount;
    }

    public String getPaymentId() { return paymentId; }
    public String getPayerAccountId() { return payerAccountId; }
    public String getMerchantAccountId() { return merchantAccountId; }
    public String getCurrency() { return currency; }
    public long getAuthorizedAmount() { return authorizedAmount; }
    public synchronized long getCapturedAmount() { return capturedAmount; }
    public synchronized long getRefundedAmount() { return refundedAmount; }
    public synchronized long getSettledAmount() { return settledAmount; }
    public synchronized boolean isVoided() { return voided; }
    public Instant getCreatedAt() { return createdAt; }

    public synchronized long capturableAmount() { return authorizedAmount - capturedAmount; }
    public synchronized long refundableAmount() { return capturedAmount - refundedAmount; }
    /** 已捕获、未退款且尚未结算的金额 */
    public synchronized long unsettledAmount() { return capturedAmount - refundedAmount - settledAmount; }

    public synchronized void capture(long amount) {
        capturedAmount += amount;
    }

    public synchronized void refund(long amount) {
        refundedAmount += amount;
    }

    public synchronized void settle(long amount) {
        settledAmount += amount;
    }

    public synchronized void voidRemaining() {
        voided = true;
    }

    public synchronized PaymentStatus status() {
        if (voided) {
            return PaymentStatus.VOIDED;
        }
        if (capturedAmount > 0 && refundedAmount == capturedAmount) {
            return PaymentStatus.REFUNDED;
        }
        if (refundedAmount > 0) {
            return PaymentStatus.PARTIALLY_REFUNDED;
        }
        if (capturedAmount == authorizedAmount) {
            return PaymentStatus.CAPTURED;
        }
        if (capturedAmount > 0) {
            return PaymentStatus.PARTIALLY_CAPTURED;
        }
        return PaymentStatus.AUTHORIZED;
    }
}
