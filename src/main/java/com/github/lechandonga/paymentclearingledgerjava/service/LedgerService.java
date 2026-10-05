package com.github.lechandonga.paymentclearingledgerjava.service;

import com.github.lechandonga.paymentclearingledgerjava.exception.BusinessException;
import com.github.lechandonga.paymentclearingledgerjava.exception.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.model.Account;
import com.github.lechandonga.paymentclearingledgerjava.model.Entry;
import com.github.lechandonga.paymentclearingledgerjava.model.Journal;
import com.github.lechandonga.paymentclearingledgerjava.model.TxnType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存总账：账户表、凭证表与过账逻辑。
 * 账户建模（同一币种）：
 *   {acct}        客户可用余额（借方余额资产账户，不允许为负）
 *   {acct}#HELD   客户授权占用（贷方余额账户，不允许为负）
 *   CLEARING:{ccy}              清算挂账户（每笔凭证内借贷互抵，恒为 0）
 *   ACQUIRER_PAYABLE:{ccy}      收单待结算款（捕获累计 - 已退款 - 已结算）
 *   MERCHANT_ACQUIRED:{ccy}     商户已结算款
 *   OPENING:{ccy}               开户注入资金的对方科目
 */
@Service
public class LedgerService {

    public static final String CLEARING = "CLEARING";
    public static final String ACQUIRER_PAYABLE = "ACQUIRER_PAYABLE";
    public static final String MERCHANT_ACQUIRED = "MERCHANT_ACQUIRED";
    public static final String OPENING = "OPENING";
    public static final String HELD_SUFFIX = "#HELD";

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<Long, Journal> journalsBySeq = new ConcurrentHashMap<>();
    private final Map<String, List<Journal>> journalsByPayment = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0L);
    private final Map<String, Boolean> currencies = new ConcurrentHashMap<>();

    public static String heldAccount(String accountId) { return accountId + HELD_SUFFIX; }
    public static String sysAccount(String role, String currency) { return role + ":" + currency; }

    public boolean accountExists(String accountId) { return accounts.containsKey(accountId); }
    public boolean currencySupported(String currency) { return currency != null && currencies.containsKey(currency); }

    public Account getAccount(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, accountId);
        }
        return account;
    }

    /**
     * 开立客户账户并注入期初余额（借贷平衡：借客户可用 / 贷 OPENING）。
     * 必须在账户锁保护下调用。
     */
    public Account createAccount(String accountId, String currency, long opening) {
        if (accounts.containsKey(accountId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_EXISTS, accountId);
        }
        ensureSystemAccounts(currency);
        Account customer = new Account(accountId, currency, 0L, true, false);
        accounts.put(accountId, customer);
        accounts.put(heldAccount(accountId), new Account(heldAccount(accountId), currency, 0L, false, false));
        if (opening > 0L) {
            post(currency, null, TxnType.OPENING, "OPENING:" + accountId, List.of(
                    new Entry(accountId, currency, opening, 0L, "opening balance"),
                    new Entry(sysAccount(OPENING, currency), currency, 0L, opening, "opening balance")));
        }
        return customer;
    }

    private void ensureSystemAccounts(String currency) {
        if (currencies.putIfAbsent(currency, Boolean.TRUE) == null) {
            accounts.put(sysAccount(CLEARING, currency), new Account(sysAccount(CLEARING, currency), currency, 0L, true, false));
            accounts.put(sysAccount(ACQUIRER_PAYABLE, currency), new Account(sysAccount(ACQUIRER_PAYABLE, currency), currency, 0L, true, false));
            accounts.put(sysAccount(MERCHANT_ACQUIRED, currency), new Account(sysAccount(MERCHANT_ACQUIRED, currency), currency, 0L, true, false));
            accounts.put(sysAccount(OPENING, currency), new Account(sysAccount(OPENING, currency), currency, 0L, false, false));
        }
    }

    /**
     * 过账一张凭证：校验借贷平衡与币种一致后，原子地逐笔改动账户余额。
     * 必须在相关账户锁保护下调用。任何一步失败抛出异常，前面的改动回滚。
     */
    public Journal post(String currency, String paymentId, TxnType txnType,
                        String idempotencyKey, List<Entry> entries) {
        long dr = entries.stream().mapToLong(Entry::dr).sum();
        long cr = entries.stream().mapToLong(Entry::cr).sum();
        if (dr != cr || dr == 0L) {
            throw new IllegalStateException("unbalanced journal: dr=" + dr + ", cr=" + cr);
        }
        for (Entry e : entries) {
            if (!e.currency().equals(currency)) {
                throw new IllegalStateException("mixed currency journal");
            }
        }
        Map<String, Long> netByAccount = new LinkedHashMap<>();
        for (Entry e : entries) {
            netByAccount.merge(e.accountId(), e.dr() - e.cr(), Long::sum);
        }
        List<Account> targets = new ArrayList<>();
        for (Map.Entry<String, Long> net : netByAccount.entrySet()) {
            Account account = getAccount(net.getKey());
            boolean held = net.getKey().endsWith(HELD_SUFFIX);
            long current = held ? account.getHeldBalance() : account.getAvailableBalance();
            long next = current + net.getValue();
            boolean violates = held ? next < 0L
                    : (account.isNaturalDebit() ? next < 0L : next > 0L);
            if (!account.isAllowNegative() && violates) {
                throw new IllegalStateException("posting would make " + net.getKey()
                        + " negative: current=" + current + ", net=" + net.getValue());
            }
            targets.add(account);
        }

        long seq = sequence.incrementAndGet();
        String journalId = "J" + seq;
        Journal journal = new Journal(journalId, currency, paymentId, txnType, idempotencyKey, seq, entries);

        for (Map.Entry<String, Long> net : netByAccount.entrySet()) {
            Account account = getAccount(net.getKey());
            if (net.getKey().endsWith(HELD_SUFFIX)) {
                account.addHeld(net.getValue());
            } else {
                account.addAvailable(net.getValue());
            }
        }
        journalsBySeq.put(seq, journal);
        if (paymentId != null) {
            journalsByPayment.computeIfAbsent(paymentId, k -> new ArrayList<>()).add(journal);
        }
        return journal;
    }

    public List<Journal> allJournals() {
        return journalsBySeq.keySet().stream().sorted()
                .map(journalsBySeq::get).toList();
    }

    public List<Journal> journalsOf(String paymentId) {
        return journalsByPayment.getOrDefault(paymentId, List.of())
                .stream().sorted(Comparator.comparingLong(Journal::sequence)).toList();
    }

    /**
     * 试算平衡：按币种汇总每个账户的借贷发生额与净额；
     * 调用方可据此校验每个币种 totalDr == totalCr。
     */
    public Map<String, List<com.github.lechandonga.paymentclearingledgerjava.dto.Responses.TrialBalanceItem>> trialBalance() {
        Map<String, Map<String, long[]>> byCurrency = new LinkedHashMap<>();
        for (Journal journal : allJournals()) {
            Map<String, long[]> byAccount = byCurrency.computeIfAbsent(journal.currency(), k -> new LinkedHashMap<>());
            for (Entry e : journal.entries()) {
                long[] dc = byAccount.computeIfAbsent(e.accountId(), k -> new long[2]);
                dc[0] += e.dr();
                dc[1] += e.cr();
            }
        }
        Map<String, List<com.github.lechandonga.paymentclearingledgerjava.dto.Responses.TrialBalanceItem>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, long[]>> cur : byCurrency.entrySet()) {
            List<com.github.lechandonga.paymentclearingledgerjava.dto.Responses.TrialBalanceItem> items = new ArrayList<>();
            for (Map.Entry<String, long[]> acc : cur.getValue().entrySet()) {
                long[] dc = acc.getValue();
                items.add(new com.github.lechandonga.paymentclearingledgerjava.dto.Responses.TrialBalanceItem(
                        cur.getKey(), acc.getKey(), dc[0], dc[1], dc[0] - dc[1]));
            }
            result.put(cur.getKey(), items);
        }
        return result;
    }

    /** 仅供测试重置内存账本。 */
    public void clearAll() {
        accounts.clear();
        journalsBySeq.clear();
        journalsByPayment.clear();
        currencies.clear();
        sequence.set(0L);
    }
}
