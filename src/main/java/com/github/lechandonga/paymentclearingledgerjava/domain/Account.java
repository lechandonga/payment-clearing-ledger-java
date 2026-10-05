package com.github.lechandonga.paymentclearingledgerjava.domain;

/**
 * 账本账户。balance 为最小货币单位，仅允许在 LedgerService 的全局锁内变更，
 * 因此不会出现负余额或并发丢失更新。
 */
public class Account {

    private final String accountId;
    private final AccountType type;
    private final String currency;
    private long balance;
    /** 授权占用金额（仅 PAYER 账户使用），available = balance - heldAmount */
    private long heldAmount;

    public Account(String accountId, AccountType type, String currency) {
        this.accountId = accountId;
        this.type = type;
        this.currency = currency;
    }

    public String getAccountId() { return accountId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public long getBalance() { return balance; }
    public long getHeldAmount() { return heldAmount; }
    public long getAvailableBalance() { return balance - heldAmount; }

    public void applyDelta(long delta) {
        long next = balance + delta;
        if (next < 0 && type != AccountType.EXTERNAL) {
            throw new IllegalStateException("account " + accountId + " would go negative: " + next);
        }
        balance = next;
    }

    public void hold(long amount) {
        if (getAvailableBalance() < amount) {
            throw new IllegalStateException("insufficient available balance on " + accountId);
        }
        heldAmount += amount;
    }

    public void releaseHold(long amount) {
        if (heldAmount < amount) {
            throw new IllegalStateException("hold underflow on " + accountId);
        }
        heldAmount -= amount;
    }
}
