package com.github.lechandonga.paymentclearingledgerjava.service;

import com.github.lechandonga.paymentclearingledgerjava.domain.Account;
import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存复式账本。所有过账在内部监视器锁内完成：
 * 校验分币种借贷平衡 -> 校验账户币种一致 -> 原子应用余额变动（失败即整体拒绝）。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    public synchronized Account createAccount(String accountId, AccountType type, String currency) {
        Account existing = accounts.get(accountId);
        if (existing != null) {
            return existing;
        }
        Account account = new Account(accountId, type, currency);
        accounts.put(accountId, account);
        log.info("[ledger] account created id={} type={} currency={}", accountId, type, currency);
        return account;
    }

    public Account getAccount(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + accountId);
        }
        return account;
    }

    public Optional<Account> findAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public String platformClearingAccountId(String currency) {
        return "PLATFORM-CLEARING-" + currency;
    }

    public synchronized Account platformClearingAccount(String currency) {
        return createAccount(platformClearingAccountId(currency), AccountType.PLATFORM_CLEARING, currency);
    }

    /** 授权占用：PAYER 账户 available -> held，不产生资金分录（无余额变动）。 */
    public synchronized void hold(String accountId, long amount) {
        getAccount(accountId).hold(amount);
    }

    /** 释放授权占用。 */
    public synchronized void releaseHold(String accountId, long amount) {
        getAccount(accountId).releaseHold(amount);
    }

    /**
     * 过账一笔分录。要求：单币种、借贷平衡、账户存在且币种一致、不产生负余额。
     * 任一校验失败则整笔拒绝，账户余额保持不变。
     */
    public synchronized JournalEntry post(JournalEntry.EntryType type, String referenceId, List<EntryLine> lines) {
        validateBalanced(lines);
        Map<String, Long> deltas = new HashMap<>();
        for (EntryLine line : lines) {
            Account account = getAccount(line.accountId());
            if (!account.getCurrency().equals(line.currency())) {
                throw new BizException(ErrorCode.CURRENCY_MISMATCH,
                        "account " + line.accountId() + " currency " + account.getCurrency()
                                + " != line currency " + line.currency());
            }
            deltas.merge(line.accountId(), line.signedAmount(), Long::sum);
        }
        for (Map.Entry<String, Long> delta : deltas.entrySet()) {
            Account account = getAccount(delta.getKey());
            if (account.getType() != AccountType.EXTERNAL
                    && account.getBalance() + delta.getValue() < 0) {
                throw new BizException(ErrorCode.INSUFFICIENT_FUNDS,
                        "posting would drive account " + delta.getKey() + " negative");
            }
        }
        deltas.forEach((id, delta) -> getAccount(id).applyDelta(delta));
        JournalEntry entry = new JournalEntry(UUID.randomUUID().toString(), type, referenceId, lines, null);
        journal.add(entry);
        log.info("[ledger] posted type={} ref={} entryId={} lines={}", type, referenceId, entry.entryId(), lines.size());
        return entry;
    }

    private void validateBalanced(List<EntryLine> lines) {
        if (lines.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "journal entry must have lines");
        }
        String currency = lines.get(0).currency();
        long debit = 0;
        long credit = 0;
        for (EntryLine line : lines) {
            if (!currency.equals(line.currency())) {
                throw new BizException(ErrorCode.CURRENCY_MISMATCH,
                        "cross-currency lines are not allowed in one entry");
            }
            if (line.direction() == EntryLine.Direction.DEBIT) {
                debit += line.amount();
            } else {
                credit += line.amount();
            }
        }
        if (debit != credit) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "unbalanced entry: debit=" + debit + " credit=" + credit);
        }
    }

    public synchronized List<JournalEntry> entries() {
        return List.copyOf(journal);
    }

    public synchronized List<JournalEntry> entriesByReference(String referenceId) {
        return journal.stream().filter(e -> e.referenceId().equals(referenceId)).toList();
    }
}
