package com.github.lechandonga.paymentclearingledgerjava.exception;

import java.time.Instant;

public record ApiError(String code, String message, String detail, Instant timestamp) {
    public static ApiError of(ErrorCode code, String detail) {
        return new ApiError(code.name(), code.getDescription(), detail, Instant.now());
    }
}
