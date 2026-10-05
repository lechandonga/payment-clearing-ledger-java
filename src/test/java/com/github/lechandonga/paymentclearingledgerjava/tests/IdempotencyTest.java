package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyService;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等重放：同键同参返回与首次一致；同键不同参返回 409 冲突；缺键返回 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    @Autowired
    MockMvc mvc;
    @Autowired
    LedgerService ledger;
    @Autowired
    IdempotencyService idempotencyService;

    private String createFundedPayer() throws Exception {
        String payer = "payer-" + UUID.randomUUID();
        String merchant = "merchant-" + UUID.randomUUID();
        mvc.perform(post("/accounts").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + payer + "\",\"type\":\"PAYER\",\"currency\":\"CNY\"}"))
                .andExpect(status().is(201));
        mvc.perform(post("/accounts").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + merchant + "\",\"type\":\"MERCHANT\",\"currency\":\"CNY\"}"))
                .andExpect(status().is(201));
        mvc.perform(post("/accounts/" + payer + "/deposit")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000,\"currency\":\"CNY\"}"))
                .andExpect(status().is(200));
        return payer + "|" + merchant;
    }

    @Test
    @DisplayName("幂等: 同键同参重放返回首次结果且不重复授权")
    void replayReturnsSameResult() throws Exception {
        String[] accounts = createFundedPayer().split("\\|");
        String key = UUID.randomUUID().toString();
        String body = "{\"payerAccountId\":\"" + accounts[0] + "\",\"merchantAccountId\":\""
                + accounts[1] + "\",\"amount\":8000,\"currency\":\"CNY\"}";

        MvcResult first = mvc.perform(post("/payments/authorize")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(201)).andReturn();
        MvcResult replay = mvc.perform(post("/payments/authorize")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(201)).andReturn();

        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals("true", replay.getResponse().getHeader("X-Idempotent-Replay"));
        assertEquals(8_000, ledger.getAccount(accounts[0]).getHeldAmount());
        log.info("[test] replayReturnsSameResult 通过: 重放响应逐字节一致, 占用未翻倍");
    }

    @Test
    @DisplayName("幂等: 同键不同参返回 409 IDEMPOTENCY_KEY_CONFLICT 且不覆盖原结果")
    void sameKeyDifferentParamsConflicts() throws Exception {
        String[] accounts = createFundedPayer().split("\\|");
        String key = UUID.randomUUID().toString();
        String body1 = "{\"payerAccountId\":\"" + accounts[0] + "\",\"merchantAccountId\":\""
                + accounts[1] + "\",\"amount\":8000,\"currency\":\"CNY\"}";
        String body2 = "{\"payerAccountId\":\"" + accounts[0] + "\",\"merchantAccountId\":\""
                + accounts[1] + "\",\"amount\":9000,\"currency\":\"CNY\"}";

        mvc.perform(post("/payments/authorize").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().is(201));
        mvc.perform(post("/payments/authorize").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertEquals(8_000, ledger.getAccount(accounts[0]).getHeldAmount());
        log.info("[test] sameKeyDifferentParamsConflicts 通过: 原结果保留, 占用仍为 8000");
    }

    @Test
    @DisplayName("幂等: 缺少 Idempotency-Key 的写请求返回 400")
    void missingKeyRejected() throws Exception {
        mvc.perform(post("/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerAccountId\":\"x\",\"merchantAccountId\":\"y\",\"amount\":1,\"currency\":\"CNY\"}"))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        log.info("[test] missingKeyRejected 通过");
    }

    @Test
    @DisplayName("幂等: 捕获重复提交不重复过账")
    void captureReplayDoesNotDoublePost() throws Exception {
        String[] accounts = createFundedPayer().split("\\|");
        String authBody = "{\"payerAccountId\":\"" + accounts[0] + "\",\"merchantAccountId\":\""
                + accounts[1] + "\",\"amount\":8000,\"currency\":\"CNY\"}";
        MvcResult auth = mvc.perform(post("/payments/authorize")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(authBody))
                .andExpect(status().is(201)).andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(auth.getResponse().getContentAsString(), "$.paymentId");

        String captureKey = UUID.randomUUID().toString();
        MvcResult c1 = mvc.perform(post("/payments/" + paymentId + "/capture")
                        .header("Idempotency-Key", captureKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":8000}"))
                .andExpect(status().is(200)).andReturn();
        MvcResult c2 = mvc.perform(post("/payments/" + paymentId + "/capture")
                        .header("Idempotency-Key", captureKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":8000}"))
                .andExpect(status().is(200)).andReturn();
        assertEquals(c1.getResponse().getContentAsString(), c2.getResponse().getContentAsString());
        long clearingBalance = ledger.getAccount("PLATFORM-CLEARING-CNY").getBalance();
        assertEquals(8_000, clearingBalance);
        assertTrue(ledger.entriesByReference(paymentId).stream()
                .filter(e -> e.type().name().equals("CAPTURE")).count() == 1);
        log.info("[test] captureReplayDoesNotDoublePost 通过: 清算户余额={}, 捕获分录仅 1 笔", clearingBalance);
    }
}
