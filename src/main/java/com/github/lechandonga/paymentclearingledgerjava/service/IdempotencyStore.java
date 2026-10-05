package com.github.lechandonga.paymentclearingledgerjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lechandonga.paymentclearingledgerjava.exception.BusinessException;
import com.github.lechandonga.paymentclearingledgerjava.exception.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.model.IdempotencyRecord;
import com.github.lechandonga.paymentclearingledgerjava.model.TxnType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等记录存储。同一 (操作类型, 幂等键) 只能对应一份请求参数指纹；
 * 重复提交返回首次响应，参数不同则抛 {@link ErrorCode#IDEMPOTENCY_CONFLICT}。
 * 所有读写都必须在对应幂等键锁 + 业务资源锁内完成。
 */
@Component
public class IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    private static String key(TxnType txnType, String idempotencyKey) {
        return txnType.name() + ":" + idempotencyKey;
    }

    /**
     * 校验并返回已存在的记录：参数指纹一致时可安全重放；不一致直接冲突。
     */
    public IdempotencyRecord replayOrConflict(TxnType txnType, String idempotencyKey,
                                              String requestFingerprint) {
        IdempotencyRecord existing = records.get(key(txnType, idempotencyKey));
        if (existing == null) {
            return null;
        }
        if (!existing.requestFingerprint().equals(requestFingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "key=" + idempotencyKey + ", firstRequest=" + existing.requestFingerprint()
                            + ", replayRequest=" + requestFingerprint);
        }
        return existing;
    }

    public IdempotencyRecord find(TxnType txnType, String idempotencyKey) {
        return records.get(key(txnType, idempotencyKey));
    }

    public void save(TxnType txnType, String idempotencyKey, String requestFingerprint,
                     JsonNode firstResponse, int httpStatus) {
        records.putIfAbsent(key(txnType, idempotencyKey),
                new IdempotencyRecord(txnType, idempotencyKey, requestFingerprint, firstResponse, httpStatus));
    }

    public void clearAll() {
        records.clear();
    }
}
