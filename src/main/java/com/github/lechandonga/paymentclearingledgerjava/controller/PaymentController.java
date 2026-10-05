package com.github.lechandonga.paymentclearingledgerjava.controller;

import com.github.lechandonga.paymentclearingledgerjava.dto.Requests;
import com.github.lechandonga.paymentclearingledgerjava.dto.Responses;
import com.github.lechandonga.paymentclearingledgerjava.model.Entry;
import com.github.lechandonga.paymentclearingledgerjava.model.Journal;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;
    private final LedgerService ledger;

    public PaymentController(PaymentService paymentService, LedgerService ledger) {
        this.paymentService = paymentService;
        this.ledger = ledger;
    }

    @PostMapping("/payments/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.PaymentResponse authorize(@RequestBody Requests.AuthorizeRequest request) {
        return paymentService.authorize(request);
    }

    @PostMapping("/payments/{paymentId}/captures")
    public Responses.PaymentResponse capture(@PathVariable String paymentId,
                                             @RequestBody Requests.PaymentActionRequest request) {
        return paymentService.capture(paymentId, request);
    }

    @PostMapping("/payments/{paymentId}/voids")
    public Responses.PaymentResponse voidPayment(@PathVariable String paymentId,
                                                 @RequestBody Requests.PaymentActionRequest request) {
        return paymentService.voidPayment(paymentId, request);
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public Responses.PaymentResponse refund(@PathVariable String paymentId,
                                            @RequestBody Requests.PaymentActionRequest request) {
        return paymentService.refund(paymentId, request);
    }

    @GetMapping("/payments/{paymentId}")
    public Responses.PaymentResponse get(@PathVariable String paymentId) {
        var payment = paymentService.getPayment(paymentId);
        return new Responses.PaymentResponse(payment.getPaymentId(), payment.getAccountId(),
                payment.getCurrency(), payment.status(), payment.getAuthorizedAmount(),
                payment.getCapturedAmount(), payment.getRefundedAmount(), payment.getVoidedAmount(),
                payment.remainingAuth(), payment.refundable());
    }

    @GetMapping("/payments/{paymentId}/journals")
    public List<Responses.JournalResponse> journals(@PathVariable String paymentId) {
        paymentService.getPayment(paymentId);
        return ledger.journalsOf(paymentId).stream().map(PaymentController::toJournalResponse).toList();
    }

    @GetMapping("/journals")
    public List<Responses.JournalResponse> allJournals() {
        return ledger.allJournals().stream().map(PaymentController::toJournalResponse).toList();
    }

    static Responses.JournalResponse toJournalResponse(Journal journal) {
        long dr = journal.entries().stream().mapToLong(Entry::dr).sum();
        long cr = journal.entries().stream().mapToLong(Entry::cr).sum();
        return new Responses.JournalResponse(journal.journalId(), journal.currency(),
                journal.paymentId(), journal.txnType().name(), journal.idempotencyKey(),
                journal.sequence(), journal.entries(), dr, cr);
    }
}
