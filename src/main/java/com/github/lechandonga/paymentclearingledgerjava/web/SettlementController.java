package com.github.lechandonga.paymentclearingledgerjava.web;

import com.github.lechandonga.paymentclearingledgerjava.domain.SettlementBatch;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyService;
import com.github.lechandonga.paymentclearingledgerjava.service.SettlementService;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Requests.SettleRequest;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Responses.BatchView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final IdempotencyService idempotency;

    public SettlementController(SettlementService settlementService, IdempotencyService idempotency) {
        this.settlementService = settlementService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> settle(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody SettleRequest req) {
        return idempotency.execute(key, "settle:" + req.currency(), () -> {
            SettlementBatch batch = settlementService.settle(req.currency());
            return PaymentController.json(201, BatchView.of(batch));
        });
    }

    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId) {
        return BatchView.of(settlementService.getBatch(batchId));
    }

    @GetMapping
    public List<BatchView> list() {
        return settlementService.batches().stream().map(BatchView::of).toList();
    }
}
