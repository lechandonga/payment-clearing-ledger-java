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
import com.github.lechandonga.paymentclearingledgerjava.model.Payment;
import com.github.lechandonga.paymentclearingledgerjava.model.PaymentStatus;
import com.github.lechandonga.paymentclearingledgerjava.model.TxnType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PaymentService {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    private final LockManager lockManager;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public PaymentService(LockManager lockManager, LedgerService ledger,
                          IdempotencyStore idempotency, ObjectMapper objectMapper) {
        this.lockManager = lockManager;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public Payment getPayment(String paymentId) {
        Payment payment = payments.get(paymentId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, paymentId);
        }
        return payment;
    }

    public void clearAll() {
        payments.clear();
    }

    public Responses.PaymentResponse authorize(Requests.AuthorizeRequest request) {
        String paymentId = requireText(request.paymentId(), "paymentId");
        String accountId = requireText(request.accountId(), "accountId");
        String currency = requireText(request.currency(), "currency");
        String idem = requireText(request.idempotencyKey(), "idempotencyKey");
        long amount = requirePositive(request.amount());

        List<ReentrantLock> held = lockManager.lockAll(List.of(
                LockManager.idemKey(TxnType.AUTHORIZE.name(), idem),
                LockManager.accountKey(accountId),
                LockManager.accountKey(LedgerService.heldAccount(accountId)),
                LockManager.accountKey(LedgerService.sysAccount(LedgerService.CLEARING, currency)),
                LockManager.paymentKey(paymentId)));
        try {
            String fingerprint = "AUTHORIZE|" + paymentId + "|" + accountId + "|" + currency + "|" + amount;
            JsonNode replay = replayIfPresent(TxnType.AUTHORIZE, idem, fingerprint);
            if (replay != null) {
                return objectMapper.convertValue(replay, Responses.PaymentResponse.class);
            }
            if (payments.containsKey(paymentId)) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS, paymentId);
            }
            Account account = ledger.getAccount(accountId);
            if (!account.getCurrency().equals(currency)) {
                throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                        "account=" + account.getCurrency() + ", request=" + currency);
            }
            if (!ledger.currencySupported(currency)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "unsupported currency: " + currency);
            }
            if (account.getAvailableBalance() < amount) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_FUNDS,
                        "available=" + account.getAvailableBalance() + ", requested=" + amount);
            }

            Journal journal = ledger.post(currency, paymentId, TxnType.AUTHORIZE, idem, List.of(
                    new Entry(LedgerService.heldAccount(accountId), currency, amount, 0L, "authorize: freeze funds"),
                    new Entry(LedgerService.sysAccount(LedgerService.CLEARING, currency), currency, 0L, amount, "authorize: transit"),
                    new Entry(LedgerService.sysAccount(LedgerService.CLEARING, currency), currency, amount, 0L, "authorize: transit"),
                    new Entry(accountId, currency, 0L, amount, "authorize: reserve available")));
            Payment payment = new Payment(paymentId, accountId, currency, amount);
            payments.put(paymentId, payment);
            Responses.PaymentResponse response = toResponse(payment, journal);
            remember(TxnType.AUTHORIZE, idem, fingerprint, response, 201);
            return response;
        } finally {
            lockManager.unlockAll(held);
        }
    }

    public Responses.PaymentResponse capture(String paymentId, Requests.PaymentActionRequest request) {
        return settleAction(paymentId, request, TxnType.CAPTURE);
    }

    public Responses.PaymentResponse voidPayment(String paymentId, Requests.PaymentActionRequest request) {
        return settleAction(paymentId, request, TxnType.VOID);
    }

    public Responses.PaymentResponse refund(String paymentId, Requests.PaymentActionRequest request) {
        return settleAction(paymentId, request, TxnType.REFUND);
    }

    private Responses.PaymentResponse settleAction(String paymentId,
                                                   Requests.PaymentActionRequest request,
                                                   TxnType txnType) {
        String idem = requireText(request.idempotencyKey(), "idempotencyKey");
        long amount = requirePositive(request.amount());
        requireText(paymentId, "paymentId");

        Payment payment = getPayment(paymentId);
        String accountId = payment.getAccountId();
        String currency = payment.getCurrency();
        List<ReentrantLock> held = lockManager.lockAll(List.of(
                LockManager.idemKey(txnType.name(), idem),
                LockManager.paymentKey(paymentId),
                LockManager.accountKey(accountId),
                LockManager.accountKey(LedgerService.heldAccount(accountId)),
                LockManager.accountKey(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, currency))));
        try {
            String fingerprint = txnType + "|" + paymentId + "|" + amount;
            JsonNode replay = replayIfPresent(txnType, idem, fingerprint);
            if (replay != null) {
                return objectMapper.convertValue(replay, Responses.PaymentResponse.class);
            }

            Journal journal = switch (txnType) {
                case CAPTURE -> doCapture(payment, amount, idem);
                case VOID -> doVoid(payment, amount, idem);
                case REFUND -> doRefund(payment, amount, idem);
                default -> throw new IllegalStateException("unexpected txn type " + txnType);
            };
            Responses.PaymentResponse response = toResponse(payment, journal);
            remember(txnType, idem, fingerprint, response, 200);
            return response;
        } finally {
            lockManager.unlockAll(held);
        }
    }

    private Journal doCapture(Payment payment, long amount, String idem) {
        if (payment.status() == PaymentStatus.VOIDED) {
            throw new BusinessException(ErrorCode.ILLEGAL_STATE_VOIDED, payment.getPaymentId());
        }
        if (amount > payment.capturable()) {
            throw new BusinessException(ErrorCode.CAPTURE_EXCEEDS_AUTH,
                    "request=" + amount + ", remainingAuth=" + payment.capturable());
        }
        String ccy = payment.getCurrency();
        Journal journal = ledger.post(ccy, payment.getPaymentId(), TxnType.CAPTURE, idem, List.of(
                new Entry(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, ccy), ccy, amount, 0L, "capture: funds due to merchant"),
                new Entry(LedgerService.heldAccount(payment.getAccountId()), ccy, 0L, amount, "capture: release hold")));
        payment.addCaptured(amount);
        return journal;
    }

    private Journal doVoid(Payment payment, long amount, String idem) {
        if (payment.status() == PaymentStatus.VOIDED) {
            throw new BusinessException(ErrorCode.ILLEGAL_STATE_VOIDED, payment.getPaymentId());
        }
        if (amount > payment.remainingAuth()) {
            throw new BusinessException(ErrorCode.VOID_EXCEEDS_REMAINING,
                    "request=" + amount + ", remainingAuth=" + payment.remainingAuth());
        }
        String ccy = payment.getCurrency();
        Journal journal = ledger.post(ccy, payment.getPaymentId(), TxnType.VOID, idem, List.of(
                new Entry(payment.getAccountId(), ccy, amount, 0L, "void: restore available"),
                new Entry(LedgerService.heldAccount(payment.getAccountId()), ccy, 0L, amount, "void: release hold")));
        payment.addVoided(amount);
        return journal;
    }

    private Journal doRefund(Payment payment, long amount, String idem) {
        if (payment.status() == PaymentStatus.VOIDED) {
            throw ILLEGAL_STATE_VOIDED_OR_NOTHING(payment);
        }
        if (amount > payment.refundable()) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_CAPTURED,
                    "request=" + amount + ", refundable=" + payment.refundable());
        }
        String ccy = payment.getCurrency();
        Journal journal = ledger.post(ccy, payment.getPaymentId(), TxnType.REFUND, idem, List.of(
                new Entry(payment.getAccountId(), ccy, amount, 0L, "refund: back to customer"),
                new Entry(LedgerService.sysAccount(LedgerService.ACQUIRER_PAYABLE, ccy), ccy, 0L, amount, "refund: reduce payable")));
        payment.addRefunded(amount);
        return journal;
    }

    private static BusinessException ILLEGAL_STATE_VOIDED_OR_NOTHING(Payment payment) {
        return new BusinessException(ErrorCode.ILLEGAL_STATE_VOIDED,
                payment.getPaymentId() + " status=" + payment.status());
    }

    private JsonNode replayIfPresent(TxnType txnType, String idem, String fingerprint) {
        var record = idempotency.replayOrConflict(txnType, idem, fingerprint);
        return record == null ? null : record.firstResponse();
    }

    private void remember(TxnType txnType, String idem, String fingerprint,
                          Responses.PaymentResponse response, int httpStatus) {
        idempotency.save(txnType, idem, fingerprint, objectMapper.valueToTree(response), httpStatus);
    }

    private Responses.PaymentResponse toResponse(Payment payment, Journal journal) {
        return new Responses.PaymentResponse(payment.getPaymentId(), payment.getAccountId(),
                payment.getCurrency(), payment.status(), payment.getAuthorizedAmount(),
                payment.getCapturedAmount(), payment.getRefundedAmount(), payment.getVoidedAmount(),
                payment.remainingAuth(), payment.refundable());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " must not be blank");
        }
        return value;
    }

    private static long requirePositive(Long value) {
        if (value == null || value <= 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "amount must be positive");
        }
        return value;
    }
}
