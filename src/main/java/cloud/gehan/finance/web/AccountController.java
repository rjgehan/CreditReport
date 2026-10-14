package cloud.gehan.finance.web;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.ConnectionRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.Balances;
import cloud.gehan.finance.service.Categorizer;
import cloud.gehan.finance.service.ConnectionService;
import cloud.gehan.finance.service.SyncService;
import cloud.gehan.finance.web.Views.AccountView;
import cloud.gehan.finance.web.Views.ConnectionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountRepository accounts;
    private final ConnectionRepository connections;
    private final TransactionRepository txns;
    private final ConnectionService connectionService;
    private final SyncService sync;
    private final Balances balances;
    private final Categorizer categorizer;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public AccountController(AccountRepository accounts, ConnectionRepository connections, TransactionRepository txns,
            ConnectionService connectionService, SyncService sync, Balances balances, Categorizer categorizer,
            TransactionTemplate tx, AppProperties props) {
        this.accounts = accounts;
        this.connections = connections;
        this.txns = txns;
        this.connectionService = connectionService;
        this.sync = sync;
        this.balances = balances;
        this.categorizer = categorizer;
        this.tx = tx;
        this.props = props;
    }

    // --- Accounts ------------------------------------------------------------------

    @GetMapping("/accounts")
    public List<AccountView> list() {
        return accounts.findAll().stream()
                .sorted(Comparator.comparing((Account a) -> a.getType().ordinal())
                        .thenComparing(a -> -Math.abs(a.getBalanceCents())))
                .map(AccountView::of).toList();
    }

    public record NewAccount(@NotBlank String name, String institution, @NotNull AccountType type, long balanceCents,
            Long creditLimitCents) {
    }

    /** An account you keep up to date yourself: PCS, a house, a loan nobody syncs. */
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@RequestBody @Valid NewAccount body) {
        return tx.execute(s -> {
            Account a = new Account(null, "manual-" + System.nanoTime(), body.name().trim(), body.type());
            a.setInstitution(body.institution());
            a.setCreditLimitCents(body.creditLimitCents());
            a = accounts.save(a);
            balances.record(a, LocalDate.now(props.zoneId()), body.balanceCents(), true);
            return AccountView.of(a);
        });
    }

    public record AccountPatch(String name, AccountType type, Boolean hidden, Long creditLimitCents,
            Double aprPercent, LocalDate paymentDueDate, Long minimumPaymentCents, Long statementBalanceCents) {
    }

    @PatchMapping("/accounts/{id}")
    public AccountView update(@PathVariable Long id, @RequestBody AccountPatch body) {
        return tx.execute(s -> {
            Account a = accounts.findById(id).orElseThrow();
            if (body.name() != null && !body.name().isBlank()) {
                a.setName(body.name().trim());
            }
            if (body.hidden() != null) {
                a.setHidden(body.hidden());
            }
            if (body.creditLimitCents() != null) {
                a.setCreditLimitCents(body.creditLimitCents() <= 0 ? null : body.creditLimitCents());
            }
            if (body.aprPercent() != null) {
                a.setAprPercent(body.aprPercent());
            }
            if (body.paymentDueDate() != null) {
                a.setPaymentDueDate(body.paymentDueDate());
            }
            if (body.minimumPaymentCents() != null) {
                a.setMinimumPaymentCents(body.minimumPaymentCents());
            }
            if (body.statementBalanceCents() != null) {
                a.setStatementBalanceCents(body.statementBalanceCents());
            }
            if (body.type() != null && body.type() != a.getType()) {
                // A credit card's debt is negative; a balance typed in as positive while
                // the account was mislabelled flips with the label.
                if (body.type().liability() != a.getType().liability()) {
                    a.setBalanceCents(-a.getBalanceCents());
                }
                a.setType(body.type());
                // Default categories depend on the account type.
                categorizer.categorize(txns.findAll((root, q, cb) -> cb.equal(root.get("account"), a)));
            }
            return AccountView.of(a);
        });
    }

    public record BalanceUpdate(long balanceCents, LocalDate day) {
    }

    @PostMapping("/accounts/{id}/balance")
    public AccountView setBalance(@PathVariable Long id, @RequestBody BalanceUpdate body) {
        return tx.execute(s -> {
            Account a = accounts.findById(id).orElseThrow();
            LocalDate today = LocalDate.now(props.zoneId());
            LocalDate day = body.day() == null ? today : body.day();
            balances.record(a, day, body.balanceCents(), !day.isBefore(today));
            return AccountView.of(a);
        });
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tx.executeWithoutResult(s -> {
            Account a = accounts.findById(id).orElseThrow();
            if (!a.isManual()) {
                throw new IllegalStateException("Synced accounts come back on the next sync. Hide it, or remove its connection.");
            }
            connectionService.deleteAccountData(a);
        });
    }

    // --- Connections ---------------------------------------------------------------

    @GetMapping("/connections")
    public List<ConnectionView> connections() {
        return connections.findAllByOrderByCreatedAtAsc().stream()
                .map(c -> ConnectionView.of(c, accounts.findByConnection(c).size()))
                .toList();
    }

    public record SimpleFinSetup(@NotBlank String setupToken) {
    }

    @PostMapping("/connections/simplefin")
    public ConnectionService.Added addSimpleFin(@RequestBody @Valid SimpleFinSetup body) {
        return connectionService.addSimpleFin(body.setupToken());
    }

    /** What Teller Connect's onSuccess hands the browser. */
    public record TellerEnrollment(@NotBlank String accessToken, String enrollmentId, String institutionName) {
    }

    @PostMapping("/connections/teller")
    public ConnectionService.Added addTeller(@RequestBody @Valid TellerEnrollment body) {
        return connectionService.addTeller(body.accessToken(), body.enrollmentId(), body.institutionName());
    }

    /** After Teller Connect repaired a login: clear the error and pull straight away. */
    @PostMapping("/connections/{id}/sync")
    public Map<String, Object> syncOne(@PathVariable Long id) {
        String error = sync.syncOne(id);
        return error == null ? Map.of("ok", true) : Map.of("ok", false, "error", error);
    }

    @PostMapping("/sync")
    public Map<String, Object> syncAll() {
        List<String> errors = sync.syncAll();
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConnection(@PathVariable Long id) {
        Connection c = connections.findById(id).orElseThrow();
        connectionService.delete(c.getId());
    }
}
