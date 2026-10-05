package com.github.lechandonga.paymentclearingledgerjava.model;

/**
 * 支付单据。金额一律为最小货币单位（long），不使用浮点。
 */
public class Payment {
    private final String paymentId;
    private final String accountId;
    private final String currency;
    private final long authorizedAmount;
    private long capturedAmount;
    private long refundedAmount;
    private long voidedAmount;

    public Payment(String paymentId, String accountId, String currency, long authorizedAmount) {
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.currency = currency;
        this.authorizedAmount = authorizedAmount;
    }

    public String getPaymentId() { return paymentId; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public long getAuthorizedAmount() { return authorizedAmount; }
    public long getCapturedAmount() { return capturedAmount; }
    public long getRefundedAmount() { return refundedAmount; }
    public long getVoidedAmount() { return voidedAmount; }

    public long remainingAuth() { return authorizedAmount - capturedAmount - voidedAmount; }
    public long capturable() { return remainingAuth(); }
    public long refundable() { return capturedAmount - refundedAmount; }

    public void addCaptured(long amount) { capturedAmount += amount; }
    public void addRefunded(long amount) { refundedAmount += amount; }
    public void addVoided(long amount) { voidedAmount += amount; }

    public PaymentStatus status() {
        if (voidedAmount > 0L && capturedAmount == 0L && voidedAmount == authorizedAmount) {
            return PaymentStatus.VOIDED;
        }
        if (refundedAmount > 0L && refundedAmount == capturedAmount) {
            return PaymentStatus.REFUNDED;
        }
        if (refundedAmount > 0L) {
            return PaymentStatus.PARTIAL_REFUNDED;
        }
        if (capturedAmount == authorizedAmount) {
            return PaymentStatus.CAPTURED;
        }
        if (capturedAmount > 0L) {
            return PaymentStatus.PARTIAL_CAPTURED;
        }
        return PaymentStatus.AUTHORIZED;
    }
}
