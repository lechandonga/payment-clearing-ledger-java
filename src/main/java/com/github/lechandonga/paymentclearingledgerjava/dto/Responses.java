package com.github.lechandonga.paymentclearingledgerjava.dto;

import com.github.lechandonga.paymentclearingledgerjava.model.Entry;
import com.github.lechandonga.paymentclearingledgerjava.model.PaymentStatus;

import java.util.List;

public final class Responses {
    private Responses() {}

    public record AccountResponse(String accountId, String currency,
                                  long availableBalance, long heldBalance) {}

    public record PaymentResponse(String paymentId, String accountId, String currency,
                                  PaymentStatus status, long authorizedAmount,
                                  long capturedAmount, long refundedAmount, long voidedAmount,
                                  long remainingAuth, long refundable) {}

    public record JournalResponse(String journalId, String currency, String paymentId,
                                  String txnType, String idempotencyKey, long sequence,
                                  List<Entry> entries, long totalDr, long totalCr) {}

    public record SettlementResponse(String batchId, String currency, String idempotencyKey,
                                     long settledAmount,
                                     List<String> journalIds,
                                     long acquirerPayableAfter, long merchantAcquiredAfter) {}

    public record TrialBalanceItem(String currency, String accountId,
                                   long debitSum, long creditSum, long net) {}
}
