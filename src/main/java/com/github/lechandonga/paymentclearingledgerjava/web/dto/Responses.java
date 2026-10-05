package com.github.lechandonga.paymentclearingledgerjava.web.dto;

import com.github.lechandonga.paymentclearingledgerjava.domain.Payment;
import com.github.lechandonga.paymentclearingledgerjava.domain.PaymentStatus;
import com.github.lechandonga.paymentclearingledgerjava.domain.SettlementBatch;

import java.time.Instant;
import java.util.Map;

public final class Responses {

    private Responses() {
    }

    public record PaymentView(String paymentId, PaymentStatus status, String currency,
                              long authorizedAmount, long capturedAmount, long refundedAmount,
                              long settledAmount, String payerAccountId, String merchantAccountId) {
        public static PaymentView of(Payment p) {
            return new PaymentView(p.getPaymentId(), p.status(), p.getCurrency(),
                    p.getAuthorizedAmount(), p.getCapturedAmount(), p.getRefundedAmount(),
                    p.getSettledAmount(), p.getPayerAccountId(), p.getMerchantAccountId());
        }
    }

    public record AccountView(String accountId, String type, String currency,
                              long balance, long heldAmount, long availableBalance) {
    }

    public record BatchView(String batchId, String currency, long totalAmount,
                            Map<String, Long> merchantAmounts, String journalEntryId,
                            Instant createdAt) {
        public static BatchView of(SettlementBatch b) {
            return new BatchView(b.batchId(), b.currency(), b.totalAmount(),
                    b.merchantAmounts(), b.journalEntryId(), b.createdAt());
        }
    }

    public record ApiError(String code, String message) {
    }
}
