package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(BusinessLogExtension.class)
@DisplayName("对账：每凭证借贷平衡、最小单位记账、试算表按币种平衡")
public class LedgerReconciliationTest extends ApiTestSupport {

    @Test
    @DisplayName("完整链路（授权/部分捕获/撤销/退款/结算）每张凭证借贷相等")
    void everyJournalBalanced() throws Exception {
        createAccount("A-RC-1", "USD", 1_000_00L);
        authorize("P-RC-1", "A-RC-1", "USD", 600_00L, newKey());
        capture("P-RC-1", 400_00L, newKey());
        voidPay("P-RC-1", 200_00L, newKey());
        refund("P-RC-1", 150_00L, newKey());
        settle("USD", newKey());

        JsonNode journals = body(rest.getForEntity("/api/journals", String.class));
        for (JsonNode journal : journals) {
            assertEquals(journal.path("totalDr").asLong(), journal.path("totalCr").asLong(),
                    "journal " + journal.path("journalId").asText() + " 必须借贷平衡");
            assertEquals(true, journal.path("totalDr").asLong() > 0);
            String currency = journal.path("currency").asText();
            for (JsonNode entry : journal.path("entries")) {
                assertEquals(currency, entry.path("currency").asText(), "凭证内不得混币种");
            }
        }
    }

    @Test
    @DisplayName("试算平衡表：各币种借方合计等于贷方合计，系统账户余额可解释")
    void trialBalancePerCurrency() throws Exception {
        createAccount("A-RC-2", "USD", 1_000_00L);
        createAccount("A-RC-2E", "EUR", 500_00L);
        authorize("P-RC-2", "A-RC-2", "USD", 300_00L, newKey());
        capture("P-RC-2", 200_00L, newKey());
        refund("P-RC-2", 50_00L, newKey());
        settle("USD", newKey());
        authorize("P-RC-2E", "A-RC-2E", "EUR", 100_00L, newKey());
        capture("P-RC-2E", 100_00L, newKey());

        JsonNode tb = body(rest.getForEntity("/api/reconciliation/trial-balance", String.class));
        assertNotNull(tb.get("USD"));
        assertNotNull(tb.get("EUR"));

        assertCurrencyBalanced(tb, "USD");
        assertCurrencyBalanced(tb, "EUR");

        long usdPayable = findNet(tb, "USD", "ACQUIRER_PAYABLE:USD");
        long usdMerchant = findNet(tb, "USD", "MERCHANT_ACQUIRED:USD");
        assertEquals(0L, usdPayable, "USD 已结算，待结算应为 0");
        assertEquals(150_00L, usdMerchant, "USD 商户已结算 = 150");

        long eurPayable = findNet(tb, "EUR", "ACQUIRER_PAYABLE:EUR");
        assertEquals(100_00L, eurPayable, "EUR 尚未结算，待结算=100");

        var usdAccount = body(rest.getForEntity("/api/accounts/A-RC-2", String.class));
        assertEquals(750_00L, usdAccount.path("availableBalance").asLong(),
                "客户可用 = 期初1000 - 授权300 + 退款50 = 750");
        assertEquals(100_00L, usdAccount.path("heldBalance").asLong(),
                "剩余 100 授权仍占用（未捕获未撤销）");
    }

    private void assertCurrencyBalanced(JsonNode tb, String currency) {
        long dr = 0L;
        long cr = 0L;
        for (JsonNode item : tb.get(currency)) {
            dr += item.path("debitSum").asLong();
            cr += item.path("creditSum").asLong();
        }
        assertEquals(dr, cr, "币种 " + currency + " 试算必须平衡");
    }

    private long findNet(JsonNode tb, String currency, String accountId) {
        for (JsonNode item : tb.get(currency)) {
            if (accountId.equals(item.path("accountId").asText())) {
                return item.path("net").asLong();
            }
        }
        throw new AssertionError("account not in trial balance: " + accountId);
    }
}
