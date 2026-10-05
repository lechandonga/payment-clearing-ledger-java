package com.github.lechandonga.paymentclearingledgerjava.exception;

import org.springframework.http.HttpStatus;

/**
 * 可区分的业务错误码。
 */
public enum ErrorCode {
    INVALID_REQUEST("请求参数非法（缺字段/金额非正/币种不支持）", HttpStatus.BAD_REQUEST),
    CURRENCY_MISMATCH("同一单据链路内币种必须一致，禁止跨币种操作", HttpStatus.CONFLICT),
    PAYMENT_NOT_FOUND("支付单据不存在", HttpStatus.NOT_FOUND),
    PAYMENT_ALREADY_EXISTS("支付单号已存在", HttpStatus.CONFLICT),
    ACCOUNT_NOT_FOUND("账户不存在", HttpStatus.NOT_FOUND),
    ACCOUNT_ALREADY_EXISTS("账户已存在", HttpStatus.CONFLICT),
    ILLEGAL_STATE_VOIDED("单据已撤销，禁止捕获或退款", HttpStatus.CONFLICT),
    CAPTURE_EXCEEDS_AUTH("捕获累计金额超过授权金额", HttpStatus.CONFLICT),
    VOID_EXCEEDS_REMAINING("撤销金额超过剩余可撤销授权额", HttpStatus.CONFLICT),
    REFUND_EXCEEDS_CAPTURED("退款累计金额超过已捕获金额", HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS("可用余额不足，授权失败", HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT("幂等键已用于不同参数的请求", HttpStatus.CONFLICT),
    SETTLEMENT_NOTHING_DUE("该币种当前无待结算资金", HttpStatus.CONFLICT);

    private final String description;
    private final HttpStatus httpStatus;

    ErrorCode(String description, HttpStatus httpStatus) {
        this.description = description;
        this.httpStatus = httpStatus;
    }

    public String getDescription() { return description; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
