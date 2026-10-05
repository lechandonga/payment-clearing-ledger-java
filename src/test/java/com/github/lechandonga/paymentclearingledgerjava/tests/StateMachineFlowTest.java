package com.github.lechandonga.paymentclearingledgerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessLogExtension.class)
@DisplayName("支付受理：授权-捕获-撤销-退款状态机流转")
public class StateMachineFlowTest extends ApiTestSupport {

    private void setupAuth(String paymentId, String accountId, long amount) {
        createAccount(accountId, "USD", 1_000_00L);
        authorize(paymentId, accountId, "USD", amount, newKey());
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode());
        try {
            assertEquals(code, errorCode(response));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("授权成功：状态 AUTHORIZED，可用余额转占用")
    void authorizeFreezesFunds() throws Exception {
        setupAuth("P-SM-1", "A-SM-1", 30_00L);
        var payment = body(rest.getForEntity("/api/payments/P-SM-1", String.class));
        assertEquals("AUTHORIZED", payment.path("status").asText());
        assertEquals(30_00L, payment.path("remainingAuth").asLong());
        var account = body(rest.getForEntity("/api/accounts/A-SM-1", String.class));
        assertEquals(970_00L, account.path("availableBalance").asLong());
        assertEquals(30_00L, account.path("heldBalance").asLong());
    }

    @Test
    @DisplayName("部分捕获：PARTIAL_CAPTURED，再全额捕获变 CAPTURED，占用逐次释放")
    void partialThenFullCapture() throws Exception {
        setupAuth("P-SM-2", "A-SM-2", 100_00L);
        assertEquals("PARTIAL_CAPTURED", body(capture("P-SM-2", 40_00L, newKey())).path("status").asText());
        assertEquals("CAPTURED", body(capture("P-SM-2", 60_00L, newKey())).path("status").asText());
        var account = body(rest.getForEntity("/api/accounts/A-SM-2", String.class));
        assertEquals(0L, account.path("heldBalance").asLong());
        assertEquals(900_00L, account.path("availableBalance").asLong());
    }

    @Test
    @DisplayName("超额捕获被拒绝：CAPTURE_EXCEEDS_AUTH，余额与占用不变")
    void captureOverAuthRejected() throws Exception {
        setupAuth("P-SM-3", "A-SM-3", 50_00L);
        assertError(capture("P-SM-3", 50_01L, newKey()), HttpStatus.CONFLICT, "CAPTURE_EXCEEDS_AUTH");
        var account = body(rest.getForEntity("/api/accounts/A-SM-3", String.class));
        assertEquals(50_00L, account.path("heldBalance").asLong());
        assertEquals(950_00L, account.path("availableBalance").asLong());
    }

    @Test
    @DisplayName("全额撤销：VOIDED，占用释放回可用余额")
    void fullVoid() throws Exception {
        setupAuth("P-SM-4", "A-SM-4", 80_00L);
        assertEquals("VOIDED", body(voidPay("P-SM-4", 80_00L, newKey())).path("status").asText());
        var account = body(rest.getForEntity("/api/accounts/A-SM-4", String.class));
        assertEquals(1_000_00L, account.path("availableBalance").asLong());
        assertEquals(0L, account.path("heldBalance").asLong());
    }

    @Test
    @DisplayName("部分捕获后撤销剩余授权合法；再捕获被 CAPTURE_EXCEEDS_AUTH 拒绝")
    void voidRemainingAfterPartialCapture() throws Exception {
        setupAuth("P-SM-5", "A-SM-5", 100_00L);
        capture("P-SM-5", 40_00L, newKey());
        var payment = body(voidPay("P-SM-5", 60_00L, newKey()));
        assertEquals("PARTIAL_CAPTURED", payment.path("status").asText());
        assertEquals(0L, payment.path("remainingAuth").asLong());
        assertError(capture("P-SM-5", 1L, newKey()), HttpStatus.CONFLICT, "CAPTURE_EXCEEDS_AUTH");
    }

    @Test
    @DisplayName("撤销额超过剩余授权：VOID_EXCEEDS_REMAINING")
    void voidOverRemainingRejected() {
        setupAuth("P-SM-6", "A-SM-6", 100_00L);
        capture("P-SM-6", 30_00L, newKey());
        assertError(voidPay("P-SM-6", 70_01L, newKey()), HttpStatus.CONFLICT, "VOID_EXCEEDS_REMAINING");
    }

    @Test
    @DisplayName("已撤销单据上捕获与退款均被 ILLEGAL_STATE_VOIDED 拒绝")
    void operationsOnVoidedRejected() {
        setupAuth("P-SM-7", "A-SM-7", 10_00L);
        voidPay("P-SM-7", 10_00L, newKey());
        assertError(capture("P-SM-7", 10_00L, newKey()), HttpStatus.CONFLICT, "ILLEGAL_STATE_VOIDED");
        assertError(refund("P-SM-7", 10_00L, newKey()), HttpStatus.CONFLICT, "ILLEGAL_STATE_VOIDED");
    }

    @Test
    @DisplayName("未捕获先退款被 REFUND_EXCEEDS_CAPTURED 拒绝")
    void refundWithoutCaptureRejected() {
        setupAuth("P-SM-8", "A-SM-8", 10_00L);
        assertError(refund("P-SM-8", 1L, newKey()), HttpStatus.CONFLICT, "REFUND_EXCEEDS_CAPTURED");
    }

    @Test
    @DisplayName("部分退款 PARTIAL_REFUNDED，退满后 REFUNDED，超额退款被拒")
    void partialThenFullRefund() throws Exception {
        setupAuth("P-SM-9", "A-SM-9", 100_00L);
        capture("P-SM-9", 100_00L, newKey());
        var first = body(refund("P-SM-9", 30_00L, newKey()));
        assertEquals("PARTIAL_REFUNDED", first.path("status").asText());
        assertEquals(70_00L, first.path("refundable").asLong());
        assertEquals("REFUNDED", body(refund("P-SM-9", 70_00L, newKey())).path("status").asText());
        assertError(refund("P-SM-9", 1L, newKey()), HttpStatus.CONFLICT, "REFUND_EXCEEDS_CAPTURED");
    }

    @Test
    @DisplayName("余额不足授权被 INSUFFICIENT_FUNDS 拒绝且不产生任何分录")
    void insufficientFundsRejected() {
        createAccount("A-SM-10", "USD", 5_00L);
        assertError(authorize("P-SM-10", "A-SM-10", "USD", 6_00L, newKey()),
                HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS");
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity("/api/payments/P-SM-10/journals", String.class).getStatusCode());
    }

    @Test
    @DisplayName("非法金额返回 INVALID_REQUEST；不存在单据返回 PAYMENT_NOT_FOUND")
    void invalidRequests() {
        createAccount("A-SM-11", "USD", 100_00L);
        assertError(authorize("P-SM-11", "A-SM-11", "USD", 0L, newKey()),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertError(capture("P-NOT-EXIST", 1L, newKey()), HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("重复授权单号返回 PAYMENT_ALREADY_EXISTS")
    void duplicatePaymentId() {
        setupAuth("P-SM-12", "A-SM-12", 10_00L);
        assertError(authorize("P-SM-12", "A-SM-12", "USD", 10_00L, newKey()),
                HttpStatus.CONFLICT, "PAYMENT_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("跨币种操作账户返回 CURRENCY_MISMATCH；同账户币种一致但币种未开户仍按不支持处理")
    void currencyMismatch() {
        createAccount("A-SM-13", "USD", 100_00L);
        assertError(authorize("P-SM-13", "A-SM-13", "EUR", 10_00L, newKey()),
                HttpStatus.CONFLICT, "CURRENCY_MISMATCH");
        assertError(authorize("P-SM-13B", "A-SM-13", "JPY", 10_00L, newKey()),
                HttpStatus.CONFLICT, "CURRENCY_MISMATCH");
    }
}
