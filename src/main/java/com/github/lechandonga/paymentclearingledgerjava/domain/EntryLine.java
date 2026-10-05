package com.github.lechandonga.paymentclearingledgerjava.domain;

/** 分录行：同一账户同一币种的一次借贷。 */
public record EntryLine(String accountId, Direction direction, long amount, String currency) {

    public enum Direction { DEBIT, CREDIT }

    public EntryLine {
        if (amount <= 0) {
            throw new IllegalArgumentException("entry line amount must be positive");
        }
    }

    /** 借为正、贷为负的有符号金额，便于余额核算。 */
    public long signedAmount() {
        return direction == Direction.DEBIT ? amount : -amount;
    }
}
