package com.github.lechandonga.paymentclearingledgerjava.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局锁注册表。所有资源键共用同一个命名空间并按字典序加锁，
 * 因而任意两个线程的加锁顺序都是同一个全序，不会发生死锁。
 * 键前缀：I=幂等键, P=支付单, A=账户, C=币种。
 */
@Component
public class LockManager {

    private static final Comparator<String> KEY_ORDER = Comparator.naturalOrder();

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock get(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /** 按全局全序加锁，返回已持有的锁（按逆序释放）。 */
    public List<ReentrantLock> lockAll(List<String> keys) {
        List<String> ordered = keys.stream().distinct().sorted(KEY_ORDER).toList();
        List<ReentrantLock> held = new java.util.ArrayList<>(ordered.size());
        try {
            for (String key : ordered) {
                ReentrantLock lock = get(key);
                lock.lock();
                held.add(lock);
            }
            return held;
        } catch (RuntimeException ex) {
            unlockAll(held);
            throw ex;
        }
    }

    public void unlockAll(List<ReentrantLock> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            held.get(i).unlock();
        }
    }

    public static String idemKey(String txnType, String key) { return "I:" + txnType + ":" + key; }
    public static String paymentKey(String paymentId) { return "P:" + paymentId; }
    public static String accountKey(String accountId) { return "A:" + accountId; }
}
