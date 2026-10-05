package com.github.lechandonga.paymentclearingledgerjava.controller;

import com.github.lechandonga.paymentclearingledgerjava.dto.Requests;
import com.github.lechandonga.paymentclearingledgerjava.dto.Responses;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.service.SettlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountingController {

    private final SettlementService settlementService;
    private final LedgerService ledger;

    public AccountingController(SettlementService settlementService, LedgerService ledger) {
        this.settlementService = settlementService;
        this.ledger = ledger;
    }

    @PostMapping("/accounts")
    public Responses.AccountResponse createAccount(@RequestBody Requests.CreateAccountRequest request) {
        return settlementService.createAccount(request);
    }

    @GetMapping("/accounts/{accountId}")
    public Responses.AccountResponse account(@PathVariable String accountId) {
        return settlementService.accountView(accountId);
    }

    @PostMapping("/settlements")
    public Responses.SettlementResponse settle(@RequestBody Requests.SettleRequest request) {
        return settlementService.settle(request);
    }

    @GetMapping("/reconciliation/trial-balance")
    public Map<String, List<Responses.TrialBalanceItem>> trialBalance() {
        return ledger.trialBalance();
    }
}
