package com.github.lechandonga.paymentclearingledgerjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.paymentclearingledgerjava.domain.Payment;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Requests.AmountRequest;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Requests.AuthorizeRequest;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Responses.PaymentView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    @PostMapping("/authorize")
    public ResponseEntity<String> authorize(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody AuthorizeRequest req) throws Exception {
        String fingerprint = MAPPER.writeValueAsString(req);
        return idempotency.execute(key, "authorize:" + fingerprint, () -> {
            Payment p = paymentService.authorize(req.paymentId(), req.payerAccountId(),
                    req.merchantAccountId(), req.amount(), req.currency());
            return json(201, PaymentView.of(p));
        });
    }

    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<String> capture(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String paymentId,
            @RequestBody AmountRequest req) throws Exception {
        String fingerprint = "capture:" + paymentId + ":" + req.amount();
        return idempotency.execute(key, fingerprint, () ->
                json(200, PaymentView.of(paymentService.capture(paymentId, req.amount()))));
    }

    @PostMapping("/{paymentId}/void")
    public ResponseEntity<String> voidPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String paymentId) {
        return idempotency.execute(key, "void:" + paymentId, () ->
                json(200, PaymentView.of(paymentService.voidPayment(paymentId))));
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<String> refund(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String paymentId,
            @RequestBody AmountRequest req) {
        String fingerprint = "refund:" + paymentId + ":" + req.amount();
        return idempotency.execute(key, fingerprint, () ->
                json(200, PaymentView.of(paymentService.refund(paymentId, req.amount()))));
    }

    @GetMapping("/{paymentId}")
    public PaymentView get(@PathVariable String paymentId) {
        return PaymentView.of(paymentService.getPayment(paymentId));
    }

    static ResponseEntity<String> json(int status, Object body) {
        try {
            return ResponseEntity.status(status)
                    .header("Content-Type", "application/json")
                    .body(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
