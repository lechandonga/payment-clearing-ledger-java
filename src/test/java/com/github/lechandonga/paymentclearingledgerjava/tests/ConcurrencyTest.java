package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyService;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发正确性：同一单据并发捕获/退款不得超额，并发同键提交结果确定，账户不得出现负余额。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);
    private static final int THREADS = 16;

    @Autowired
    PaymentService paymentService;
    @Autowired
    LedgerService ledger;
    @Autowired
    IdempotencyService idempotencyService;

    private String payer;
    private String merchant;

    @BeforeEach
    void setUp() {
        payer = "payer-" + UUID.randomUUID();
        merchant = "merchant-" + UUID.randomUUID();
        ledger.createAccount(payer, AccountType.PAYER, "CNY");
        ledger.createAccount(merchant, AccountType.MERCHANT, "CNY");
        ledger.createAccount("EXT-" + payer, AccountType.EXTERNAL, "CNY");
        ledger.post(JournalEntry.EntryType.DEPOSIT, payer, List.of(
                new EntryLine(payer, EntryLine.Direction.DEBIT, 1_000_000, "CNY"),
                new EntryLine("EXT-" + payer, EntryLine.Direction.CREDIT, 1_000_000, "CNY")));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentLinkedQueue<Object> runConcurrently(Callable<Object> task)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    results.add(task.call());
                } catch (BizException e) {
                    results.add(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return results;
    }

    @Test
    @DisplayName("并发: 16 线程同时全额捕获同一单据, 恰好成功一次, 无重复分录")
    void concurrentCaptureOnlyOneWins() throws Exception {
        String id = paymentService.authorize(null, payer, merchant, 10_000, "CNY").getPaymentId();
        ConcurrentLinkedQueue<Object> results = runConcurrently(() -> {
            paymentService.capture(id, 10_000);
            return "OK";
        });
        long ok = results.stream().filter(r -> "OK".equals(r)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof BizException b
                        && b.getCode() == ErrorCode.CAPTURE_EXCEEDS_AUTHORIZED)
                .count();
        assertEquals(1, ok);
        assertEquals(THREADS - 1, conflicts);
        assertEquals(10_000, paymentService.getPayment(id).getCapturedAmount());
        assertEquals(1, ledger.entriesByReference(id).stream()
                .filter(e -> e.type() == JournalEntry.EntryType.CAPTURE).count());
        log.info("[test] concurrentCaptureOnlyOneWins 通过: 成功=1 拒绝={} 分录=1", conflicts);
    }

    @Test
    @DisplayName("并发: 部分捕获并发提交, 累计不超过授权额")
    void concurrentPartialCapturesNeverExceedAuthorized() throws Exception {
        String id = paymentService.authorize(null, payer, merchant, 10_000, "CNY").getPaymentId();
        runConcurrently(() -> {
            paymentService.capture(id, 1_000);
            return "OK";
        });
        long captured = paymentService.getPayment(id).getCapturedAmount();
        assertTrue(captured <= 10_000, "captured must not exceed authorized");
        assertEquals(0, captured % 1_000);
        assertTrue(ledger.getAccount(payer).getBalance() >= 0);
        log.info("[test] concurrentPartialCapturesNeverExceedAuthorized 通过: captured={}", captured);
    }

    @Test
    @DisplayName("并发: 并发退款不超过已捕获金额, 付款人余额不被多退")
    void concurrentRefundsNeverExceedCaptured() throws Exception {
        String id = paymentService.authorize(null, payer, merchant, 10_000, "CNY").getPaymentId();
        paymentService.capture(id, 10_000);
        long payerBalanceBefore = ledger.getAccount(payer).getBalance();
        runConcurrently(() -> {
            paymentService.refund(id, 1_000);
            return "OK";
        });
        long refunded = paymentService.getPayment(id).getRefundedAmount();
        assertTrue(refunded <= 10_000);
        assertEquals(payerBalanceBefore + refunded, ledger.getAccount(payer).getBalance());
        log.info("[test] concurrentRefundsNeverExceedCaptured 通过: refunded={}", refunded);
    }

    @Test
    @DisplayName("并发: 同一幂等键并发提交, 全部得到与首次一致的结果, 只执行一次")
    void concurrentSameIdempotencyKeyDeterministic() throws Exception {
        String id = paymentService.authorize(null, payer, merchant, 10_000, "CNY").getPaymentId();
        String key = "idem-" + UUID.randomUUID();
        String fingerprint = "capture:" + id + ":10000";
        AtomicInteger executions = new AtomicInteger();
        ConcurrentLinkedQueue<Object> results = runConcurrently(() ->
                idempotencyService.execute(key, fingerprint, () -> {
                    executions.incrementAndGet();
                    var p = paymentService.capture(id, 10_000);
                    return ResponseEntity.ok("{\"captured\":" + p.getCapturedAmount() + "}");
                }));
        assertEquals(1, executions.get());
        String firstBody = ((ResponseEntity<String>) results.peek()).getBody();
        for (Object r : results) {
            assertEquals(firstBody, ((ResponseEntity<String>) r).getBody());
        }
        assertEquals(10_000, paymentService.getPayment(id).getCapturedAmount());
        log.info("[test] concurrentSameIdempotencyKeyDeterministic 通过: 执行次数=1, 16 个响应一致");
    }

    @Test
    @DisplayName("并发: 并发授权同一账户, 占用不超过余额, 无负余额")
    void concurrentAuthorizationsNoNegativeBalance() throws Exception {
        runConcurrently(() -> {
            paymentService.authorize(null, payer, merchant, 100_000, "CNY");
            return "OK";
        });
        var account = ledger.getAccount(payer);
        assertTrue(account.getHeldAmount() <= 1_000_000);
        assertTrue(account.getAvailableBalance() >= 0);
        assertEquals(0, account.getHeldAmount() % 100_000);
        log.info("[test] concurrentAuthorizationsNoNegativeBalance 通过: held={}",
                account.getHeldAmount());
    }
}
