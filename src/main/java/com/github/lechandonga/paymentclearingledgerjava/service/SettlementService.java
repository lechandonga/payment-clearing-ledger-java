package com.github.lechandonga.paymentclearingledgerjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentclearingledgerjava.dto.Requests;
import com.github.lechandonga.paymentclearingledgerjava.dto.Responses;
import com.github.lechandonga.paymentclearingledgerjava.exception.BusinessException;
import com.github.lechandonga.paymentclearingledgerjava.exception.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.model.Account;
import com.github.lechandonga.paymentclearingledgerjava.model.Entry;
import com.github.lechandonga.paymentclearingledgerjava.model.Journal;
import com.github.lechandonga.paymentclearingledgerjava.model.TxnType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按币种清算与结算。一个币种一次结算批次：
 * 借 MERCHANT_ACQUIRED / 贷 ACQUIRER_PAYABLE，把待结算款清零结给商户。
 * 相同幂等键重复触发返回首次批次，绝不重复入账。
 */
@Service
public class SettlementService {

    private final LockManager lockManager;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public SettlementService(LockManager lockManager, LedgerService ledger,
                             IdempotencyStore idempotency, ObjectMapper objectMapper) {
        this.lockManager = lockManager;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public Responses.SettlementResponse settle(Requests.SettleRequest request) {
        String currency = request.currency();
        String idem = request.idempotencyKey();
        if (currency == null || currency.isBlank() || idem == null || idem.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "currency and idempotencyKey must not be blank");
        }
        if (!ledger.currencySupported(currency)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "unsupported currency: " + currency);
        }

        List<ReentrantLock> held = lockManager.lockAll(List.of(
                LockManager.idemKey(TxnType.SETTLE.name(), idem),
                LockManager.accountKey(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, currency)),
                LockManager.accountKey(LedgerService.sysAccount(LedgerService.MERCHANT_ACQUIRED, currency))));
        try {
            String fingerprint = "SETTLE|" + currency;
            var existing = idempotency.replayOrConflict(TxnType.SETTLE, idem, fingerprint);
            if (existing != null) {
                return objectMapper.convertValue(existing.firstResponse(), Responses.SettlementResponse.class);
            }

            Account payable = ledger.getAccount(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, currency));
            long amount = payable.getAvailableBalance();
            if (amount <= 0L) {
                throw new BusinessException(ErrorCode.SETTLEMENT_NOTHING_DUE, currency);
            }

            Journal journal = ledger.post(currency, null, TxnType.SETTLE, idem, List.of(
                    new Entry(LedgerService.sysAccount(LedgerService.MERCHANT_ACQUIRED, currency), currency, amount, 0L, "settlement to merchant"),
                    new Entry(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, currency), currency, 0L, amount, "settlement: clear payable")));

            String batchId = "BATCH-" + currency + "-" + journal.sequence();
            Responses.SettlementResponse response = new Responses.SettlementResponse(
                    batchId, currency, idem, amount, List.of(journal.journalId()),
                    payable.getAvailableBalance(),
                    ledger.getAccount(LedgerService.sysAccount(LedgerService.MERCHANT_ACQUIRED, currency)).getAvailableBalance());
            idempotency.save(TxnType.SETTLE, idem, fingerprint, objectMapper.valueToTree(response), 200);
            return response;
        } finally {
            lockManager.unlockAll(held);
        }
    }

    public Responses.AccountResponse createAccount(Requests.CreateAccountRequest request) {
        String accountId = request.accountId();
        String currency = request.currency();
        if (accountId == null || accountId.isBlank() || currency == null || currency.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "accountId and currency must not be blank");
        }
        long opening = request.openingBalance() == null ? 0L : request.openingBalance();
        if (opening < 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "openingBalance must be non-negative");
        }
        List<ReentrantLock> held = lockManager.lockAll(List.of(
                LockManager.accountKey(accountId),
                LockManager.accountKey(LedgerService.heldAccount(accountId))));
        try {
            if (ledger.accountExists(accountId)) {
                throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_EXISTS, accountId);
            }
            ledger.createAccount(accountId, currency, opening);
            return accountView(accountId);
        } finally {
            lockManager.unlockAll(held);
        }
    }

    public Responses.AccountResponse accountView(String accountId) {
        var account = ledger.getAccount(accountId);
        var held = ledger.getAccount(LedgerService.heldAccount(accountId));
        return new Responses.AccountResponse(accountId, account.getCurrency(),
                account.getAvailableBalance(), held.getHeldBalance());
    }
}
