package com.github.lechandonga.paymentclearingledgerjava.web.dto;

public final class Requests {

    private Requests() {
    }

    public record CreateAccountRequest(String accountId, String type, String currency) {
    }

    public record DepositRequest(long amount, String currency) {
    }

    public record AuthorizeRequest(String paymentId, String payerAccountId,
                                   String merchantAccountId, long amount, String currency) {
    }

    public record AmountRequest(long amount) {
    }

    public record SettleRequest(String currency) {
    }
}
