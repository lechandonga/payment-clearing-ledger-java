package com.github.lechandonga.paymentclearingledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyStore;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import com.github.lechandonga.paymentclearingledgerjava.dto.Requests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

/**
 * 测试公共辅助：账户/单据构造与 HTTP 调用。
 */
public abstract class ApiTestSupport {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void resetInMemoryState() {
        ledgerService.clearAll();
        paymentService.clearAll();
        idempotencyStore.clearAll();
    }

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    protected ResponseEntity<String> createAccount(String accountId, String currency, long opening) {
        return rest.postForEntity("/api/accounts",
                new Requests.CreateAccountRequest(accountId, currency, opening),
                String.class);
    }

    protected ResponseEntity<String> authorize(String paymentId, String accountId,
                                               String currency, long amount, String key) {
        return rest.postForEntity("/api/payments/authorizations",
                new Requests.AuthorizeRequest(paymentId, accountId, currency, amount, key),
                String.class);
    }

    protected ResponseEntity<String> capture(String paymentId, long amount, String key) {
        return postPaymentAction(paymentId, "captures", amount, key);
    }

    protected ResponseEntity<String> voidPay(String paymentId, long amount, String key) {
        return postPaymentAction(paymentId, "voids", amount, key);
    }

    protected ResponseEntity<String> refund(String paymentId, long amount, String key) {
        return postPaymentAction(paymentId, "refunds", amount, key);
    }

    protected ResponseEntity<String> postPaymentAction(String paymentId, String action,
                                                       long amount, String key) {
        return rest.exchange("/api/payments/" + paymentId + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(new Requests.PaymentActionRequest(amount, key)),
                String.class);
    }

    protected ResponseEntity<String> settle(String currency, String key) {
        return rest.postForEntity("/api/settlements",
                new Requests.SettleRequest(currency, key), String.class);
    }

    protected String errorCode(ResponseEntity<String> response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody());
        return node.path("code").asText();
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    protected JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
