package com.github.lechandonga.paymentclearingledgerjava.model;

import java.util.List;

/**
 * 一张记账凭证：同币种借贷必相等。
 */
public record Journal(String journalId,
                      String currency,
                      String paymentId,
                      TxnType txnType,
                      String idempotencyKey,
                      long sequence,
                      List<Entry> entries) {
    public Journal {
        entries = List.copyOf(entries);
        long dr = entries.stream().mapToLong(Entry::dr).sum();
        long cr = entries.stream().mapToLong(Entry::cr).sum();
        if (dr != cr) {
            throw new IllegalArgumentException("journal not balanced: dr=" + dr + " cr=" + cr);
        }
        if (entries.stream().anyMatch(e -> !e.currency().equals(currency))) {
            throw new IllegalArgumentException("journal contains mixed currencies");
        }
    }
}
