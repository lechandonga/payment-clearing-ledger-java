package com.github.lechandonga.paymentclearingledgerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessLogExtension.class)
@DisplayName("清算与结算：按币种汇总、批次恰好一次、跨币种隔离")
public class SettlementExactlyOnceTest extends ApiTestSupport {

    @Test
    @DisplayName("捕获-退款后的净额按币种结算一次：待结算清零、商户款增加净额")
    void settleNetAmountOnce() throws Exception {
        createAccount("A-ST-1", "USD", 1_000_00L);
        authorize("P-ST-1", "A-ST-1", "USD", 500_00L, newKey());
        capture("P-ST-1", 500_00L, newKey());
        refund("P-ST-1", 200_00L, newKey());

        String key = newKey();
        var first = body(settle("USD", key));
        assertEquals(300_00L, first.path("settledAmount").asLong());
        assertEquals(0L, first.path("acquirerPayableAfter").asLong());
        assertEquals(300_00L, first.path("merchantAcquiredAfter").asLong());

        var replay = body(settle("USD", key));
        assertEquals(first, replay);

        var noDue = settle("USD", newKey());
        assertEquals(HttpStatus.CONFLICT, noDue.getStatusCode());
        assertEquals("SETTLEMENT_NOTHING_DUE", errorCode(noDue));
    }

    @Test
    @DisplayName("同一结算批次并发触发：只有一个批次入账，其余重放同一批次")
    void concurrentSettlementExactlyOnce() throws Exception {
        createAccount("A-ST-2", "USD", 1_000_00L);
        authorize("P-ST-2", "A-ST-2", "USD", 400_00L, newKey());
        capture("P-ST-2", 400_00L, newKey());
        String key = newKey();

        var outcomes = new java.util.concurrent.CopyOnWriteArrayList<String>();
        int threads = 8;
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(new Thread(() -> outcomes.add(settle("USD", key).getBody())));
        }
        workers.forEach(Thread::start);
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(threads, outcomes.size());
        assertEquals(1, outcomes.stream().distinct().count());
        assertEquals(400_00L, objectMapper.readTree(outcomes.get(0)).path("settledAmount").asLong());

        var journals = body(rest.getForEntity("/api/journals", String.class));
        long settleJournals = journals.findValuesAsText("txnType").stream()
                .filter("SETTLE"::equals).count();
        assertEquals(1, settleJournals);
    }

    @Test
    @DisplayName("跨币种不直接相加：USD 与 EUR 分别结算，金额各自独立")
    void settlePerCurrencyIsolated() throws Exception {
        createAccount("A-ST-3U", "USD", 1_000_00L);
        createAccount("A-ST-3E", "EUR", 800_00L);
        authorize("P-ST-3U", "A-ST-3U", "USD", 100_00L, newKey());
        capture("P-ST-3U", 100_00L, newKey());
        authorize("P-ST-3E", "A-ST-3E", "EUR", 250_00L, newKey());
        capture("P-ST-3E", 250_00L, newKey());

        var usd = body(settle("USD", newKey()));
        var eur = body(settle("EUR", newKey()));
        assertEquals(100_00L, usd.path("settledAmount").asLong());
        assertEquals(250_00L, eur.path("settledAmount").asLong());
        assertEquals(100_00L, usd.path("merchantAcquiredAfter").asLong());
        assertEquals(250_00L, eur.path("merchantAcquiredAfter").asLong());
    }
}
