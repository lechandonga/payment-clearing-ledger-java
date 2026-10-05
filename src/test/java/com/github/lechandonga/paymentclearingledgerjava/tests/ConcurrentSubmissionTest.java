package com.github.lechandonga.paymentclearingledgerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessLogExtension.class)
@DisplayName("并发：同单/同账户并发下不超额、不重账、无负余额")
public class ConcurrentSubmissionTest extends ApiTestSupport {

    private <T> List<T> runParallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = pool.invokeAll(tasks);
            return futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("同一授权幂等键并发提交 N 次：全部返回首次结果且只有一张凭证")
    void concurrentSameAuthorizeKey() throws Exception {
        createAccount("A-CX-1", "USD", 1_000_00L);
        String key = newKey();
        List<Callable<Integer>> tasks = IntStream.range(0, 12)
                .<Callable<Integer>>mapToObj(i -> () ->
                        authorize("P-CX-1", "A-CX-1", "USD", 100_00L, key).getStatusCode().value())
                .toList();
        List<Integer> statuses = runParallel(tasks);
        assertEquals(12, statuses.stream().filter(s -> s == 201).count(),
                "首次 201，其余同参重放也返回首次的 201");
        var journals = body(rest.getForEntity("/api/payments/P-CX-1/journals", String.class));
        assertEquals(1, journals.size());
        var account = body(rest.getForEntity("/api/accounts/A-CX-1", String.class));
        assertEquals(900_00L, account.path("availableBalance").asLong());
        assertEquals(100_00L, account.path("heldBalance").asLong());
    }

    @Test
    @DisplayName("同一捕获请求并发提交：全部重放首次结果，累计捕获恰好一次")
    void concurrentSameCaptureKey() throws Exception {
        createAccount("A-CX-2", "USD", 1_000_00L);
        authorize("P-CX-2", "A-CX-2", "USD", 100_00L, newKey());
        String key = newKey();
        List<Callable<Integer>> tasks = IntStream.range(0, 10)
                .<Callable<Integer>>mapToObj(i -> () -> capture("P-CX-2", 100_00L, key).getStatusCode().value())
                .toList();
        List<Integer> statuses = runParallel(tasks);
        assertEquals(10, statuses.stream().filter(s -> s == 200).count());
        var payment = body(rest.getForEntity("/api/payments/P-CX-2", String.class));
        assertEquals(100_00L, payment.path("capturedAmount").asLong());
    }

    @Test
    @DisplayName("不同幂等键的并发部分捕获：成功额累计不超过授权额，其余确定性超额拒绝")
    void concurrentDistinctCapturesNeverOverCapture() throws Exception {
        createAccount("A-CX-3", "USD", 1_000_00L);
        authorize("P-CX-3", "A-CX-3", "USD", 100_00L, newKey());
        List<Callable<Integer>> tasks = IntStream.range(0, 8)
                .<Callable<Integer>>mapToObj(i -> () -> capture("P-CX-3", 30_00L, newKey()).getStatusCode().value())
                .toList();
        List<Integer> statuses = runParallel(tasks);
        long ok = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 409).count();
        assertEquals(3, ok, "100 授权下每笔 30，最多成功 3 笔");
        assertEquals(5, rejected);
        var payment = body(rest.getForEntity("/api/payments/P-CX-3", String.class));
        assertEquals(90_00L, payment.path("capturedAmount").asLong());
        assertEquals(10_00L, payment.path("remainingAuth").asLong());
    }

    @Test
    @DisplayName("不同键的并发退款：成功退款累计不超过已捕获，超额请求确定性被拒")
    void concurrentCaptureAndRefund() throws Exception {
        createAccount("A-CX-4", "USD", 1_000_00L);
        authorize("P-CX-4", "A-CX-4", "USD", 100_00L, newKey());
        capture("P-CX-4", 100_00L, newKey());
        List<Callable<Integer>> tasks = IntStream.range(0, 6)
                .<Callable<Integer>>mapToObj(i -> () -> refund("P-CX-4", 40_00L, newKey()).getStatusCode().value())
                .toList();
        List<Integer> statuses = runParallel(tasks);
        long ok = statuses.stream().filter(s -> s == 200).count();
        var payment = body(rest.getForEntity("/api/payments/P-CX-4", String.class));
        assertEquals(2, ok, "每笔退 40，最多成功 2 笔");
        assertEquals(4, statuses.stream().filter(s -> s == 409).count());
        assertEquals("PARTIAL_REFUNDED", payment.path("status").asText(),
                "仅退满 80/100，处于部分退款；剩余 20 仍可退");
        assertEquals(80_00L, payment.path("refundedAmount").asLong());
        assertEquals(20_00L, payment.path("refundable").asLong());
    }

    @Test
    @DisplayName("同一账户多笔单据并发授权：不会出现负可用余额，占用合计与授权一致")
    void concurrentAuthorizationsOnSameAccount() throws Exception {
        createAccount("A-CX-5", "USD", 500_00L);
        List<Callable<Integer>> tasks = IntStream.range(0, 10)
                .<Callable<Integer>>mapToObj(i -> () ->
                        authorize("P-CX-5-" + i, "A-CX-5", "USD", 100_00L, newKey()).getStatusCode().value())
                .toList();
        List<Integer> statuses = runParallel(tasks);
        long ok = statuses.stream().filter(s -> s == 201).count();
        long rejected = statuses.stream().filter(s -> s == 409).count();
        assertEquals(5, ok);
        assertEquals(5, rejected);
        var account = body(rest.getForEntity("/api/accounts/A-CX-5", String.class));
        assertEquals(0L, account.path("availableBalance").asLong());
        assertEquals(500_00L, account.path("heldBalance").asLong());
        Set<String> distinct = new HashSet<>(statuses.stream().map(String::valueOf).toList());
        assertTrue(distinct.contains("201") && distinct.contains("409"));
    }
}
