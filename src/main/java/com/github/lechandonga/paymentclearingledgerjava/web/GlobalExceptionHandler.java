package com.github.lechandonga.paymentclearingledgerjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Responses.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ExceptionHandler(BizException.class)
    public ResponseEntity<String> handleBiz(BizException e) throws Exception {
        log.info("[api] rejected code={} message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode().getStatus())
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsString(new ApiError(e.getCode().name(), e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleOther(Exception e) throws Exception {
        log.error("[api] unexpected error", e);
        return ResponseEntity.status(500)
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsString(
                        new ApiError(ErrorCode.VALIDATION_FAILED.name(), String.valueOf(e.getMessage()))));
    }
}
