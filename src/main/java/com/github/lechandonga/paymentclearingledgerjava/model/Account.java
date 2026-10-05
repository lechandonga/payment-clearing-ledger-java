package com.github.lechandonga.paymentclearingledgerjava.model;

/**
 * 内存账户。余额语义为借贷方向（正数），是否允许为负由 {@link #allowNegative} 控制：
 * 客户余额类账户不得为负；清算挂账户入账恒为 0 余额。
 */
public class Account {
    private final String accountId;
    private final String currency;
    /** true=借方余额账户（资产/费用类，余额恒非负）；false=贷方余额账户（负债/权益/收入类，余额恒非正）。 */
    private final boolean naturalDebit;
    private long availableBalance;
    private long heldBalance;
    private final boolean allowNegative;

    public Account(String accountId, String currency, long openingAvailable,
                   boolean naturalDebit, boolean allowNegative) {
        this.accountId = accountId;
        this.currency = currency;
        this.naturalDebit = naturalDebit;
        this.availableBalance = openingAvailable;
        this.heldBalance = 0L;
        this.allowNegative = allowNegative;
    }

    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public boolean isNaturalDebit() { return naturalDebit; }
    public long getAvailableBalance() { return availableBalance; }
    public long getHeldBalance() { return heldBalance; }
    public boolean isAllowNegative() { return allowNegative; }

    public void addAvailable(long delta) {
        long next = availableBalance + delta;
        if (!allowNegative && violatesNaturalSide(next)) {
            throw new IllegalStateException("negative balance on " + accountId);
        }
        availableBalance = next;
    }

    public void addHeld(long delta) {
        long next = heldBalance + delta;
        if (next < 0L) {
            throw new IllegalStateException("negative held amount on " + accountId);
        }
        heldBalance = next;
    }

    private boolean violatesNaturalSide(long signedBalance) {
        return naturalDebit ? signedBalance < 0L : signedBalance > 0L;
    }
}
