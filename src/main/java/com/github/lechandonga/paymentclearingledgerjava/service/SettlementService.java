package com.github.lechandonga.paymentclearingledgerjava.service;

import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.domain.Payment;
import com.github.lechandonga.paymentclearingledgerjava.domain.SettlementBatch;
import com.github.lechandonga.paymentclearingledgerjava.error.BizException;
import com.github.lechandonga.paymentclearingledgerjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.CREDIT;
import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.DEBIT;

/**
 * 清算与结算：按币种汇总已捕获未结算资金，一笔平衡分录过账到各商户账户。
 * 批次与幂等键绑定，重复触发返回原批次；结算过程按币种加锁，保证恰好一次入账。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final LedgerService ledger;
    private final PaymentService paymentService;
    private final Map<String, Object> currencyLocks = new ConcurrentHashMap<>();
    private final Map<String, SettlementBatch> batches = new ConcurrentHashMap<>();

    public SettlementService(LedgerService ledger, PaymentService paymentService) {
        this.ledger = ledger;
        this.paymentService = paymentService;
    }

    public SettlementBatch settle(String currency) {
        synchronized (currencyLocks.computeIfAbsent(currency, k -> new Object())) {
            List<Payment> pending = paymentService.paymentsWithUnsettled(currency);
            Map<String, Long> merchantAmounts = new LinkedHashMap<>();
            Map<String, Long> paymentAmounts = new LinkedHashMap<>();
            long total = 0;
            for (Payment payment : pending) {
                long amount = payment.unsettledAmount();
                merchantAmounts.merge(payment.getMerchantAccountId(), amount, Long::sum);
                paymentAmounts.put(payment.getPaymentId(), amount);
                total += amount;
            }
            String batchId = UUID.randomUUID().toString();
            if (total == 0) {
                SettlementBatch empty = new SettlementBatch(batchId, currency, 0, Map.of(), null, Instant.now());
                log.info("[settlement] batch={} currency={} nothing to settle", batchId, currency);
                return empty;
            }
            List<EntryLine> lines = new ArrayList<>();
            merchantAmounts.forEach((merchantId, amount) ->
                    lines.add(new EntryLine(merchantId, DEBIT, amount, currency)));
            lines.add(new EntryLine(ledger.platformClearingAccountId(currency), CREDIT, total, currency));
            JournalEntry entry = ledger.post(JournalEntry.EntryType.SETTLEMENT, batchId, lines);
            paymentAmounts.forEach((paymentId, amount) ->
                    paymentService.markSettled(paymentService.getPayment(paymentId), amount));
            SettlementBatch batch = new SettlementBatch(batchId, currency, total,
                    Map.copyOf(merchantAmounts), entry.entryId(), Instant.now());
            batches.put(batchId, batch);
            log.info("[settlement] batch={} currency={} total={} merchants={}",
                    batchId, currency, total, merchantAmounts.size());
            return batch;
        }
    }

    public SettlementBatch getBatch(String batchId) {
        SettlementBatch batch = batches.get(batchId);
        if (batch == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "batch not found: " + batchId);
        }
        return batch;
    }

    public List<SettlementBatch> batches() {
        return List.copyOf(batches.values());
    }
}
