package com.github.lechandonga.paymentclearingledgerjava.web;

import com.github.lechandonga.paymentclearingledgerjava.domain.Account;
import com.github.lechandonga.paymentclearingledgerjava.domain.AccountType;
import com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine;
import com.github.lechandonga.paymentclearingledgerjava.domain.JournalEntry;
import com.github.lechandonga.paymentclearingledgerjava.service.IdempotencyService;
import com.github.lechandonga.paymentclearingledgerjava.service.LedgerService;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Requests.CreateAccountRequest;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Requests.DepositRequest;
import com.github.lechandonga.paymentclearingledgerjava.web.dto.Responses.AccountView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.CREDIT;
import static com.github.lechandonga.paymentclearingledgerjava.domain.EntryLine.Direction.DEBIT;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public AccountController(LedgerService ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreateAccountRequest req) {
        String fingerprint = "create-account:" + req.accountId() + ":" + req.type() + ":" + req.currency();
        return idempotency.execute(key, fingerprint, () -> {
            Account account = ledger.createAccount(req.accountId(),
                    AccountType.valueOf(req.type()), req.currency());
            return PaymentController.json(201, view(account));
        });
    }

    /** 注入测试资金：外部资金来源户（允许为负方向由平台清算户承担，这里简化为贷记平台清算户）。 */
    @PostMapping("/{accountId}/deposit")
    public ResponseEntity<String> deposit(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String accountId,
            @RequestBody DepositRequest req) {
        String fingerprint = "deposit:" + accountId + ":" + req.amount() + ":" + req.currency();
        return idempotency.execute(key, fingerprint, () -> {
            ledger.getAccount(accountId);
            ledger.createAccount("EXTERNAL-FUNDING-" + req.currency(),
                    AccountType.EXTERNAL, req.currency());
            ledger.post(JournalEntry.EntryType.DEPOSIT, accountId, List.of(
                    new EntryLine(accountId, DEBIT, req.amount(), req.currency()),
                    new EntryLine("EXTERNAL-FUNDING-" + req.currency(), CREDIT, req.amount(), req.currency())));
            return PaymentController.json(200, view(ledger.getAccount(accountId)));
        });
    }

    @GetMapping("/{accountId}")
    public AccountView get(@PathVariable String accountId) {
        return view(ledger.getAccount(accountId));
    }

    @GetMapping("/{accountId}/entries")
    public List<JournalEntry> entries(@PathVariable String accountId) {
        return ledger.entries().stream()
                .filter(e -> e.lines().stream().anyMatch(l -> l.accountId().equals(accountId)))
                .toList();
    }

    private static AccountView view(Account a) {
        return new AccountView(a.getAccountId(), a.getType().name(), a.getCurrency(),
                a.getBalance(), a.getHeldAmount(), a.getAvailableBalance());
    }
}
