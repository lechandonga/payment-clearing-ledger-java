package com.github.lechandonga.paymentclearingledgerjava.service;

import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 写请求幂等。以 Idempotency-Key 为锁串行化同键请求：
 * - 首次执行并记录响应；
 * - 同键同参数重放：返回与首次完全一致的响应（状态码 + 报文）；
 * - 同键不同参数：抛出 IDEMPOTENCY_KEY_CONFLICT，绝不覆盖首次结果。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private record StoredResponse(String fingerprint, int status, String body) {
    }

    private final Map<String, StoredResponse> store = new ConcurrentHashMap<>();

    public ResponseEntity<String> execute(String key, String fingerprint,
                                          Supplier<ResponseEntity<String>> action) {
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "header Idempotency-Key is required for write operations");
        }
        synchronized (key.intern()) {
            StoredResponse stored = store.get(key);
            if (stored != null) {
                if (!stored.fingerprint().equals(fingerprint)) {
                    log.warn("[idempotency] conflict key={} fingerprint mismatch", key);
                    throw new BizException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "idempotency key reused with different request parameters");
                }
                log.info("[idempotency] replay key={} status={}", key, stored.status());
                return ResponseEntity.status(stored.status())
                        .header("Content-Type", "application/json")
                        .header("X-Idempotent-Replay", "true")
                        .body(stored.body());
            }
            ResponseEntity<String> response = action.get();
            store.put(key, new StoredResponse(fingerprint, response.getStatusCode().value(),
                    response.getBody()));
            return response;
        }
    }
}
