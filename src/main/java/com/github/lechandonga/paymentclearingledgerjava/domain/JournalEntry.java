package com.github.lechandonga.paymentclearingledgerjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 复式记账分录。同一币种内借贷必须平衡；不同币种不允许出现在同一分录中相加。
 */
public record JournalEntry(String entryId, EntryType type, String referenceId,
                           List<EntryLine> lines, Instant createdAt) {

    public enum EntryType {
        AUTHORIZE, CAPTURE, VOID, REFUND, SETTLEMENT, DEPOSIT
    }

    public JournalEntry {
        lines = List.copyOf(lines);
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
