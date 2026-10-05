package com.github.lechandonga.paymentclearingledgerjava.dto;

public final class Requests {
    private Requests() {}

    public record CreateAccountRequest(String accountId, String currency, Long openingBalance) {}

    public record AuthorizeRequest(String paymentId, String accountId, String currency,
                                   Long amount, String idempotencyKey) {}

    public record PaymentActionRequest(Long amount, String idempotencyKey) {}

    public record SettleRequest(String currency, String idempotencyKey) {}
}
