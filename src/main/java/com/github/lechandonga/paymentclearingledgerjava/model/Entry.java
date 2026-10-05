package com.github.lechandonga.paymentclearingledgerjava.model;

/**
 * 单条分录。借(dr)/贷(cr)二选一为正数金额。
 */
public record Entry(String accountId, String currency, long dr, long cr, String description) {
    public Entry {
        if ((dr > 0 && cr > 0) || (dr == 0 && cr == 0)) {
            throw new IllegalArgumentException("entry must have exactly one non-zero leg");
        }
    }

    public long signedAmount() { return dr - cr; }
}
