package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.PaymentStatus;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 状态机流转：授权 -> 捕获(部分/全额) -> 撤销 / 退款，非法流转必须带可区分错误码被拒绝。
 */
@SpringBootTest
class PaymentStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateMachineTest.class);

    @Autowired
    PaymentService paymentService;
    @Autowired
    LedgerService ledger;

    private String payer;
    private String merchant;

    @BeforeEach
    void setUp() {
        payer = "payer-" + UUID.randomUUID();
        merchant = "merchant-" + UUID.randomUUID();
        ledger.createAccount(payer, AccountType.PAYER, "CNY");
        ledger.createAccount(merchant, AccountType.MERCHANT, "CNY");
        ledger.createAccount("EXT-" + payer, AccountType.EXTERNAL, "CNY");
        ledger.post(com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry.EntryType.DEPOSIT,
                payer, java.util.List.of(
                        new com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine(
                                payer, com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.DEBIT,
                                100_000, "CNY"),
                        new com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine(
                                "EXT-" + payer, com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.CREDIT,
                                100_000, "CNY")));
    }

    private String authorize(long amount) {
        return paymentService.authorize(null, payer, merchant, amount, "CNY").getPaymentId();
    }

    @Test
    @DisplayName("状态机: 授权->部分捕获->全额捕获->部分退款->全额退款")
    void happyPathFullChain() {
        String id = authorize(10_000);
        assertEquals(PaymentStatus.AUTHORIZED, paymentService.statusOf(id));
        assertEquals(90_000, ledger.getAccount(payer).getAvailableBalance());
        assertEquals(10_000, ledger.getAccount(payer).getHeldAmount());

        paymentService.capture(id, 4_000);
        assertEquals(PaymentStatus.PARTIALLY_CAPTURED, paymentService.statusOf(id));
        paymentService.capture(id, 6_000);
        assertEquals(PaymentStatus.CAPTURED, paymentService.statusOf(id));
        assertEquals(0, ledger.getAccount(payer).getHeldAmount());
        assertEquals(90_000, ledger.getAccount(payer).getBalance());

        paymentService.refund(id, 3_000);
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, paymentService.statusOf(id));
        paymentService.refund(id, 7_000);
        assertEquals(PaymentStatus.REFUNDED, paymentService.statusOf(id));
        assertEquals(100_000, ledger.getAccount(payer).getBalance());
        log.info("[test] happyPathFullChain 通过: 状态流转与余额一致");
    }

    @Test
    @DisplayName("状态机: 授权后撤销释放占用, 撤销后捕获/再撤销被拒绝")
    void voidReleasesHoldAndIsTerminal() {
        String id = authorize(5_000);
        paymentService.voidPayment(id);
        assertEquals(PaymentStatus.VOIDED, paymentService.statusOf(id));
        assertEquals(0, ledger.getAccount(payer).getHeldAmount());
        assertEquals(100_000, ledger.getAccount(payer).getAvailableBalance());

        BizException capture = assertThrows(BizException.class, () -> paymentService.capture(id, 1));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, capture.getCode());
        BizException revoid = assertThrows(BizException.class, () -> paymentService.voidPayment(id));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, revoid.getCode());
        BizException refund = assertThrows(BizException.class, () -> paymentService.refund(id, 1));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, refund.getCode());
        log.info("[test] voidReleasesHoldAndIsTerminal 通过: 终态拒绝后续操作");
    }

    @Test
    @DisplayName("状态机: 超额捕获/超额退款分别以专属错误码拒绝")
    void overCaptureAndOverRefundRejected() {
        String id = authorize(10_000);
        paymentService.capture(id, 6_000);
        BizException overCapture = assertThrows(BizException.class, () -> paymentService.capture(id, 4_001));
        assertEquals(ErrorCode.CAPTURE_EXCEEDS_AUTHORIZED, overCapture.getCode());

        paymentService.refund(id, 2_000);
        BizException overRefund = assertThrows(BizException.class, () -> paymentService.refund(id, 4_001));
        assertEquals(ErrorCode.REFUND_EXCEEDS_CAPTURED, overRefund.getCode());
        assertEquals(6_000, paymentService.getPayment(id).getCapturedAmount());
        assertEquals(2_000, paymentService.getPayment(id).getRefundedAmount());
        log.info("[test] overCaptureAndOverRefundRejected 通过: 拒绝后状态未被破坏");
    }

    @Test
    @DisplayName("状态机: 部分捕获后撤销, 剩余占用释放且已捕获部分可退款")
    void voidAfterPartialCapture() {
        String id = authorize(10_000);
        paymentService.capture(id, 6_000);
        paymentService.voidPayment(id);
        assertEquals(PaymentStatus.VOIDED, paymentService.statusOf(id));
        assertEquals(0, ledger.getAccount(payer).getHeldAmount());
        paymentService.refund(id, 6_000);
        assertEquals(100_000, ledger.getAccount(payer).getBalance());
        log.info("[test] voidAfterPartialCapture 通过");
    }

    @Test
    @DisplayName("校验: 余额不足授权失败, 跨币种授权失败, 不存在的单据 404")
    void validationErrors() {
        BizException insufficient = assertThrows(BizException.class,
                () -> paymentService.authorize(null, payer, merchant, 200_000, "CNY"));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, insufficient.getCode());

        BizException currency = assertThrows(BizException.class,
                () -> paymentService.authorize(null, payer, merchant, 100, "USD"));
        assertEquals(ErrorCode.CURRENCY_MISMATCH, currency.getCode());

        BizException notFound = assertThrows(BizException.class, () -> paymentService.capture("nope", 1));
        assertEquals(ErrorCode.PAYMENT_NOT_FOUND, notFound.getCode());
        log.info("[test] validationErrors 通过");
    }
}
