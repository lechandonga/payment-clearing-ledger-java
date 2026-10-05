package com.github.lechandonga.paymentclearingledgerjava.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    CAPTURE_EXCEEDS_AUTHORIZED(HttpStatus.CONFLICT),
    REFUND_EXCEEDS_CAPTURED(HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS(HttpStatus.CONFLICT),
    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
