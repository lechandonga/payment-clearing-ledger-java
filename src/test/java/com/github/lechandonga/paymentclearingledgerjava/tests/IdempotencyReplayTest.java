package com.github.lechandonga.paymentclearingledgerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessLogExtension.class)
@DisplayName("幂等：重放一致、参数冲突可区分")
public class IdempotencyReplayTest extends ApiTestSupport {

    private void seed() {
        createAccount("A-IDEM", "USD", 1_000_00L);
    }

    @Test
    @DisplayName("授权重复提交：返回与首次完全一致，只生成一张凭证")
    void authorizeReplayReturnsFirstResult() throws Exception {
        seed();
        String key = newKey();
        var first = authorize("P-IDEM-1", "A-IDEM", "USD", 100_00L, key);
        var replay = authorize("P-IDEM-1", "A-IDEM", "USD", 100_00L, key);
        assertEquals(first.getBody(), replay.getBody());
        var journals = body(rest.getForEntity("/api/payments/P-IDEM-1/journals", String.class));
        assertEquals(1, journals.size());
        var account = body(rest.getForEntity("/api/accounts/A-IDEM", String.class));
        assertEquals(900_00L, account.path("availableBalance").asLong());
        assertEquals(100_00L, account.path("heldBalance").asLong());
    }

    @Test
    @DisplayName("同一授权幂等键携带不同金额：IDEMPOTENCY_CONFLICT，首次结果不被覆盖")
    void authorizeSameKeyDifferentAmountConflicts() throws Exception {
        seed();
        String key = newKey();
        var first = authorize("P-IDEM-2", "A-IDEM", "USD", 100_00L, key);
        var conflict = authorize("P-IDEM-2X", "A-IDEM", "USD", 200_00L, key);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", errorCode(conflict));
        var replay = authorize("P-IDEM-2", "A-IDEM", "USD", 100_00L, key);
        assertEquals(first.getBody(), replay.getBody());
    }

    @Test
    @DisplayName("捕获/退款重复提交：累计金额只生效一次")
    void captureAndRefundReplay() throws Exception {
        seed();
        authorize("P-IDEM-3", "A-IDEM", "USD", 100_00L, newKey());
        String captureKey = newKey();
        var c1 = capture("P-IDEM-3", 60_00L, captureKey);
        var c2 = capture("P-IDEM-3", 60_00L, captureKey);
        assertEquals(c1.getBody(), c2.getBody());
        var afterCapture = body(rest.getForEntity("/api/payments/P-IDEM-3", String.class));
        assertEquals(60_00L, afterCapture.path("capturedAmount").asLong());

        String refundKey = newKey();
        var r1 = refund("P-IDEM-3", 20_00L, refundKey);
        var r2 = refund("P-IDEM-3", 20_00L, refundKey);
        assertEquals(r1.getBody(), r2.getBody());
        var afterRefund = body(rest.getForEntity("/api/payments/P-IDEM-3", String.class));
        assertEquals(20_00L, afterRefund.path("refundedAmount").asLong());
    }

    @Test
    @DisplayName("同一捕获幂等键但金额不同：IDEMPOTENCY_CONFLICT，原捕获额不变")
    void captureConflictAmount() throws Exception {
        seed();
        authorize("P-IDEM-4", "A-IDEM", "USD", 100_00L, newKey());
        String key = newKey();
        capture("P-IDEM-4", 30_00L, key);
        var conflict = capture("P-IDEM-4", 40_00L, key);
        assertEquals("IDEMPOTENCY_CONFLICT", errorCode(conflict));
        var payment = body(rest.getForEntity("/api/payments/P-IDEM-4", String.class));
        assertEquals(30_00L, payment.path("capturedAmount").asLong());
    }

    @Test
    @DisplayName("首次为失败请求时，幂等键不会被占用；更正参数后同键可成功")
    void failedAttemptDoesNotReserveKey() throws Exception {
        seed();
        String key = newKey();
        var failed = capture("P-IDEM-5", 10_00L, key);
        assertEquals(HttpStatus.NOT_FOUND, failed.getStatusCode());
        authorize("P-IDEM-5", "A-IDEM", "USD", 50_00L, newKey());
        var ok = capture("P-IDEM-5", 10_00L, key);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals(10_00L, body(ok).path("capturedAmount").asLong());
        assertNotEquals("IDEMPOTENCY_CONFLICT", errorCode(capture("P-IDEM-5", 10_00L, key)));
    }
}
