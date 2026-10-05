package com.github.lechandonga.paymentclearingledgerjava.service;

import com.github.lechandonga.paymentclearingledgerjava.domain.Account;
import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.domain.Payment;
import com.github.lechandonga.paymentclearingledgerjava.domain.PaymentStatus;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.CREDIT;
import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.DEBIT;

/**
 * 支付受理：授权 / 捕获 / 撤销 / 退款。
 * 同一支付单的操作按单据锁串行化，保证并发下状态判定与记账是原子的，
 * 冲突请求得到确定性的拒绝结果而不是随机覆盖。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final LedgerService ledger;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, Object> paymentLocks = new ConcurrentHashMap<>();

    public PaymentService(LedgerService ledger) {
        this.ledger = ledger;
    }

    private Object lockOf(String paymentId) {
        return paymentLocks.computeIfAbsent(paymentId, k -> new Object());
    }

    public Payment getPayment(String paymentId) {
        Payment payment = payments.get(paymentId);
        if (payment == null) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND, "payment not found: " + paymentId);
        }
        return payment;
    }

    /** 授权：校验账户与币种后冻结付款人可用余额。 */
    public Payment authorize(String paymentId, String payerAccountId, String merchantAccountId,
                             long amount, String currency) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "amount must be positive");
        }
        String id = paymentId == null || paymentId.isBlank() ? UUID.randomUUID().toString() : paymentId;
        synchronized (lockOf(id)) {
            if (payments.containsKey(id)) {
                throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                        "payment already exists: " + id);
            }
            Account payer = ledger.getAccount(payerAccountId);
            Account merchant = ledger.getAccount(merchantAccountId);
            requireCurrency(payer, currency);
            requireCurrency(merchant, currency);
            if (payer.getType() != AccountType.PAYER || merchant.getType() != AccountType.MERCHANT) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "payer account must be PAYER and merchant account must be MERCHANT");
            }
            if (payer.getAvailableBalance() < amount) {
                throw new BizException(ErrorCode.INSUFFICIENT_FUNDS,
                        "available balance " + payer.getAvailableBalance() + " < " + amount);
            }
            ledger.hold(payerAccountId, amount);
            Payment payment = new Payment(id, payerAccountId, merchantAccountId, currency, amount);
            payments.put(id, payment);
            log.info("[payment] authorized id={} amount={} {}", id, amount, currency);
            return payment;
        }
    }

    /** 捕获：付款人冻结资金转入平台清算户。允许部分捕获，累计不得超过授权额。 */
    public Payment capture(String paymentId, long amount) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "amount must be positive");
        }
        synchronized (lockOf(paymentId)) {
            Payment payment = getPayment(paymentId);
            if (payment.isVoided()) {
                throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                        "payment " + paymentId + " is VOIDED, capture not allowed");
            }
            if (amount > payment.capturableAmount()) {
                throw new BizException(ErrorCode.CAPTURE_EXCEEDS_AUTHORIZED,
                        "capture " + amount + " exceeds remaining authorized "
                                + payment.capturableAmount());
            }
            String currency = payment.getCurrency();
            ledger.platformClearingAccount(currency);
            ledger.releaseHold(payment.getPayerAccountId(), amount);
            try {
                ledger.post(JournalEntry.EntryType.CAPTURE, paymentId, List.of(
                        new EntryLine(ledger.platformClearingAccountId(currency), DEBIT, amount, currency),
                        new EntryLine(payment.getPayerAccountId(), CREDIT, amount, currency)));
            } catch (RuntimeException e) {
                ledger.hold(payment.getPayerAccountId(), amount);
                throw e;
            }
            payment.capture(amount);
            log.info("[payment] captured id={} amount={} status={}", paymentId, amount, payment.status());
            return payment;
        }
    }

    /** 撤销：释放剩余授权占用，单据进入 VOIDED 终态（已捕获部分仍可退款）。 */
    public Payment voidPayment(String paymentId) {
        synchronized (lockOf(paymentId)) {
            Payment payment = getPayment(paymentId);
            if (payment.isVoided()) {
                throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                        "payment " + paymentId + " already VOIDED");
            }
            long remainingHold = payment.capturableAmount();
            if (remainingHold > 0) {
                ledger.releaseHold(payment.getPayerAccountId(), remainingHold);
            }
            payment.voidRemaining();
            log.info("[payment] voided id={} releasedHold={}", paymentId, remainingHold);
            return payment;
        }
    }

    /**
     * 退款：累计退款不得超过累计捕获。
     * 未结算部分从平台清算户退回付款人；已结算部分从商户账户扣回（余额不足则拒绝，不多扣不少扣）。
     */
    public Payment refund(String paymentId, long amount) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "amount must be positive");
        }
        synchronized (lockOf(paymentId)) {
            Payment payment = getPayment(paymentId);
            if (payment.refundableAmount() <= 0) {
                throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                        "payment " + paymentId + " has no captured amount to refund");
            }
            if (amount > payment.refundableAmount()) {
                throw new BizException(ErrorCode.REFUND_EXCEEDS_CAPTURED,
                        "refund " + amount + " exceeds refundable " + payment.refundableAmount());
            }
            String currency = payment.getCurrency();
            ledger.platformClearingAccount(currency);
            long fromClearing = Math.min(amount, payment.unsettledAmount());
            long fromMerchant = amount - fromClearing;
            if (fromMerchant > 0
                    && ledger.getAccount(payment.getMerchantAccountId()).getBalance() < fromMerchant) {
                throw new BizException(ErrorCode.INSUFFICIENT_FUNDS,
                        "merchant balance insufficient for refund of settled funds");
            }
            if (fromClearing > 0) {
                ledger.post(JournalEntry.EntryType.REFUND, paymentId, List.of(
                        new EntryLine(payment.getPayerAccountId(), DEBIT, fromClearing, currency),
                        new EntryLine(ledger.platformClearingAccountId(currency), CREDIT, fromClearing, currency)));
            }
            if (fromMerchant > 0) {
                ledger.post(JournalEntry.EntryType.REFUND, paymentId, List.of(
                        new EntryLine(payment.getPayerAccountId(), DEBIT, fromMerchant, currency),
                        new EntryLine(payment.getMerchantAccountId(), CREDIT, fromMerchant, currency)));
            }
            payment.refund(amount);
            log.info("[payment] refunded id={} amount={} status={}", paymentId, amount, payment.status());
            return payment;
        }
    }

    /** 结算服务调用：标记单据已结算金额（在结算币种锁内调用）。 */
    void markSettled(Payment payment, long amount) {
        synchronized (lockOf(payment.getPaymentId())) {
            payment.settle(amount);
        }
    }

    List<Payment> paymentsWithUnsettled(String currency) {
        return payments.values().stream()
                .filter(p -> p.getCurrency().equals(currency) && p.unsettledAmount() > 0)
                .toList();
    }

    private void requireCurrency(Account account, String currency) {
        if (!account.getCurrency().equals(currency)) {
            throw new BizException(ErrorCode.CURRENCY_MISMATCH,
                    "account " + account.getAccountId() + " currency " + account.getCurrency()
                            + " != " + currency);
        }
    }

    public PaymentStatus statusOf(String paymentId) {
        return getPayment(paymentId).status();
    }
}
