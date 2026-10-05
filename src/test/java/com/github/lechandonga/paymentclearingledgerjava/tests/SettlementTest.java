package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.domain.SettlementBatch;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import com.github.lechandonga.paymentclearingledgerjava.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 清算与结算：按币种汇总、借贷平衡、恰好一次入账、结算后退款从商户账户扣回。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SettlementTest {

    private static final Logger log = LoggerFactory.getLogger(SettlementTest.class);

    @Autowired
    PaymentService paymentService;
    @Autowired
    LedgerService ledger;
    @Autowired
    SettlementService settlementService;

    private String payer;
    private String merchantA;
    private String merchantB;

    @BeforeEach
    void setUp() {
        payer = "payer-" + UUID.randomUUID();
        merchantA = "merchantA-" + UUID.randomUUID();
        merchantB = "merchantB-" + UUID.randomUUID();
        ledger.createAccount(payer, AccountType.PAYER, "CNY");
        ledger.createAccount(merchantA, AccountType.MERCHANT, "CNY");
        ledger.createAccount(merchantB, AccountType.MERCHANT, "CNY");
        ledger.createAccount("EXT-" + payer, AccountType.EXTERNAL, "CNY");
        ledger.post(JournalEntry.EntryType.DEPOSIT, payer, List.of(
                new EntryLine(payer, EntryLine.Direction.DEBIT, 1_000_000, "CNY"),
                new EntryLine("EXT-" + payer, EntryLine.Direction.CREDIT, 1_000_000, "CNY")));
    }

    private String captureTo(String merchant, long amount) {
        String id = paymentService.authorize(null, payer, merchant, amount, "CNY").getPaymentId();
        paymentService.capture(id, amount);
        return id;
    }

    @Test
    @DisplayName("结算: 按币种汇总到商户, 分录借贷平衡")
    void settleAggregatesPerMerchant() {
        captureTo(merchantA, 10_000);
        captureTo(merchantA, 5_000);
        captureTo(merchantB, 7_000);

        SettlementBatch batch = settlementService.settle("CNY");
        assertEquals(22_000, batch.totalAmount());
        assertEquals(15_000, batch.merchantAmounts().get(merchantA));
        assertEquals(7_000, batch.merchantAmounts().get(merchantB));
        assertEquals(15_000, ledger.getAccount(merchantA).getBalance());
        assertEquals(7_000, ledger.getAccount(merchantB).getBalance());
        assertEquals(0, ledger.getAccount("PLATFORM-CLEARING-CNY").getBalance());

        JournalEntry entry = ledger.entriesByReference(batch.batchId()).get(0);
        long debit = entry.lines().stream()
                .filter(l -> l.direction() == EntryLine.Direction.DEBIT).mapToLong(EntryLine::amount).sum();
        long credit = entry.lines().stream()
                .filter(l -> l.direction() == EntryLine.Direction.CREDIT).mapToLong(EntryLine::amount).sum();
        assertEquals(debit, credit);
        log.info("[test] settleAggregatesPerMerchant 通过: total={} 分录平衡", batch.totalAmount());
    }

    @Test
    @DisplayName("结算: 无新捕获时再次结算为空批次, 不重复入账")
    void settleExactlyOnce() {
        captureTo(merchantA, 10_000);
        SettlementBatch first = settlementService.settle("CNY");
        assertEquals(10_000, first.totalAmount());

        SettlementBatch second = settlementService.settle("CNY");
        assertEquals(0, second.totalAmount());
        assertNull(second.journalEntryId());
        assertEquals(10_000, ledger.getAccount(merchantA).getBalance());
        assertEquals(1, ledger.entries().stream()
                .filter(e -> e.type() == JournalEntry.EntryType.SETTLEMENT).count());
        log.info("[test] settleExactlyOnce 通过: 第二次结算为空批次, 商户余额不变");
    }

    @Test
    @DisplayName("结算: 结算后新捕获进入下一批次")
    void newCapturesGoToNextBatch() {
        captureTo(merchantA, 10_000);
        SettlementBatch first = settlementService.settle("CNY");
        captureTo(merchantA, 3_000);
        SettlementBatch second = settlementService.settle("CNY");
        assertNotEquals(first.batchId(), second.batchId());
        assertEquals(3_000, second.totalAmount());
        assertEquals(13_000, ledger.getAccount(merchantA).getBalance());
        log.info("[test] newCapturesGoToNextBatch 通过");
    }

    @Test
    @DisplayName("结算: 结算前退款从清算户退回, 结算后退款从商户账户扣回")
    void refundBeforeAndAfterSettlement() {
        String p1 = captureTo(merchantA, 10_000);
        paymentService.refund(p1, 2_000);
        SettlementBatch batch = settlementService.settle("CNY");
        assertEquals(8_000, batch.totalAmount());
        assertEquals(8_000, ledger.getAccount(merchantA).getBalance());

        paymentService.refund(p1, 5_000);
        assertEquals(3_000, ledger.getAccount(merchantA).getBalance());
        assertEquals(997_000, ledger.getAccount(payer).getBalance());
        log.info("[test] refundBeforeAndAfterSettlement 通过: 两条退款路径金额精确");
    }

    @Test
    @DisplayName("结算: 跨币种独立结算, 金额不混合")
    void crossCurrencyIsolated() {
        String usdPayer = "payer-usd-" + UUID.randomUUID();
        String usdMerchant = "merchant-usd-" + UUID.randomUUID();
        ledger.createAccount(usdPayer, AccountType.PAYER, "USD");
        ledger.createAccount(usdMerchant, AccountType.MERCHANT, "USD");
        ledger.createAccount("EXT-" + usdPayer, AccountType.EXTERNAL, "USD");
        ledger.post(JournalEntry.EntryType.DEPOSIT, usdPayer, List.of(
                new EntryLine(usdPayer, EntryLine.Direction.DEBIT, 50_000, "USD"),
                new EntryLine("EXT-" + usdPayer, EntryLine.Direction.CREDIT, 50_000, "USD")));

        captureTo(merchantA, 10_000);
        String usdPayment = paymentService.authorize(null, usdPayer, usdMerchant, 4_000, "USD").getPaymentId();
        paymentService.capture(usdPayment, 4_000);

        SettlementBatch cny = settlementService.settle("CNY");
        SettlementBatch usd = settlementService.settle("USD");
        assertEquals(10_000, cny.totalAmount());
        assertEquals(4_000, usd.totalAmount());
        assertEquals(10_000, ledger.getAccount(merchantA).getBalance());
        assertEquals(4_000, ledger.getAccount(usdMerchant).getBalance());
        log.info("[test] crossCurrencyIsolated 通过: CNY={} USD={} 独立入账",
                cny.totalAmount(), usd.totalAmount());
    }
}
