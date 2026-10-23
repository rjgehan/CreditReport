package cloud.gehan.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.BuyCadence;
import cloud.gehan.finance.model.Holding;
import cloud.gehan.finance.model.RecurringBuy;
import cloud.gehan.finance.model.TradeSource;
import cloud.gehan.finance.provider.ProviderException;
import cloud.gehan.finance.provider.QuoteClient;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.HoldingRepository;
import cloud.gehan.finance.repo.RecurringBuyRepository;
import cloud.gehan.finance.service.PortfolioService;
import cloud.gehan.finance.web.Views.AccountView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Webull-style accounts priced from their holdings, and PCS-style recurring contributions. */
@RestController
@RequestMapping("/api/investments")
public class InvestmentController {

    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final RecurringBuyRepository recurring;
    private final PortfolioService portfolio;
    private final QuoteClient quotes;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final cloud.gehan.finance.service.Balances balances;

    public InvestmentController(AccountRepository accounts, HoldingRepository holdings, RecurringBuyRepository recurring,
            PortfolioService portfolio, QuoteClient quotes, TransactionTemplate tx, AppProperties props, cloud.gehan.finance.service.Balances balances) {
        this.balances = balances;
        this.accounts = accounts;
        this.holdings = holdings;
        this.recurring = recurring;
        this.portfolio = portfolio;
        this.quotes = quotes;
        this.tx = tx;
        this.props = props;
    }

    public record HoldingView(Long id, String symbol, String name, double shares, Double price, long valueCents,
            long dayChangeCents, Long costBasisCents, Instant priceUpdatedAt) {
        static HoldingView of(Holding h) {
            return new HoldingView(h.getId(), h.getSymbol(), h.getName(), h.getShares(), h.getLastPrice(),
                    h.valueCents(), h.dayChangeCents(), h.getCostBasisCents(), h.getPriceUpdatedAt());
        }
    }

    public record PlanView(Long id, String symbol, long amountCents, String cadence, int intervalDays,
            LocalDate startDate, LocalDate lastApplied, LocalDate nextDate, boolean active) {
        static PlanView of(RecurringBuy r, LocalDate today) {
            return new PlanView(r.getId(), r.getSymbol(), r.getAmountCents(), r.getCadence().name(),
                    r.getIntervalDays(), r.getStartDate(), r.getLastApplied(), r.nextAfter(today), r.isActive());
        }
    }

    /**
     * @param dayChangeCents today's move across all positions, from yesterday's close
     * @param dayChangePercent the same against yesterday's value of those positions
     */
    public record InvestmentAccount(AccountView account, Long cashCents, List<HoldingView> holdings,
            List<PlanView> plans, long dayChangeCents, Double dayChangePercent) {
    }

    @GetMapping
    public List<InvestmentAccount> list() {
        List<InvestmentAccount> out = new ArrayList<>();
        for (Account a : accounts.findAll()) {
            boolean investment = a.getType() == AccountType.BROKERAGE || a.getType() == AccountType.RETIREMENT;
            if (investment || !holdings.findByAccount(a).isEmpty()) {
                out.add(view(a));
            }
        }
        return out;
    }

    /** One account, for its own page. */
    @GetMapping("/{accountId}")
    public InvestmentAccount one(@PathVariable Long accountId) {
        return view(accounts.findById(accountId).orElseThrow());
    }

    private InvestmentAccount view(Account a) {
        LocalDate today = LocalDate.now(props.zoneId());
        List<Holding> held = holdings.findByAccount(a).stream()
                .sorted(Comparator.comparingLong(Holding::valueCents).reversed()).toList();
        long dayChange = held.stream().mapToLong(Holding::dayChangeCents).sum();
        long yesterday = held.stream().mapToLong(h -> h.valueCents() - h.dayChangeCents()).sum();
        return new InvestmentAccount(AccountView.of(a, balances.monthAgo(a, today)), a.getCashCents(),
                held.stream().map(HoldingView::of).toList(),
                recurring.findByAccount(a).stream().map(p -> PlanView.of(p, today)).toList(),
                dayChange, yesterday > 0 ? 100.0 * dayChange / yesterday : null);
    }

    public record HoldingBody(@NotBlank String symbol, double shares, Long costBasisCents) {
    }

    /** Adds a ticker or changes how many shares of it you hold. Zero shares removes it. */
    @PutMapping("/{accountId}/holdings")
    public Map<String, Object> setHolding(@PathVariable Long accountId, @RequestBody @Valid HoldingBody body) {
        String symbol = body.symbol().trim().toUpperCase(Locale.ROOT);
        // Fetching the price first also proves the ticker exists.
        QuoteClient.Quote quote = quotes.quote(symbol);
        LocalDate today = LocalDate.now(props.zoneId());
        tx.executeWithoutResult(s -> {
            Account a = accounts.findById(accountId).orElseThrow();
            Holding h = portfolio.setShares(a, symbol, Math.max(0, body.shares()), today, TradeSource.MANUAL);
            h.setLastPrice(quote.price());
            h.setPreviousClose(quote.previousClose());
            h.setName(quote.name());
            h.setPriceUpdatedAt(Instant.now());
            if (body.costBasisCents() != null) {
                h.setCostBasisCents(body.costBasisCents() <= 0 ? null : body.costBasisCents());
            }
            if (h.getShares() <= 0) {
                holdings.delete(h);
                holdings.flush();
            }
            portfolio.revalue(a);
        });
        return Map.of("ok", true);
    }

    public record CashBody(long cashCents) {
    }

    @PutMapping("/{accountId}/cash")
    public AccountView setCash(@PathVariable Long accountId, @RequestBody CashBody body) {
        return tx.execute(s -> {
            Account a = accounts.findById(accountId).orElseThrow();
            a.setCashCents(body.cashCents());
            portfolio.revalue(a);
            return AccountView.of(a);
        });
    }

    /**
     * @param startShares shares held on {@code asOf}, from a statement; or
     * @param startBalanceCents the account's value on {@code asOf}, converted to shares at that day's close
     * @param firstContribution the first contribution after {@code asOf} to apply
     */
    public record PlanBody(@NotBlank String symbol, long amountCents, @NotNull BuyCadence cadence, Integer intervalDays,
            @NotNull LocalDate firstContribution, Double startShares, Long startBalanceCents, LocalDate asOf) {
    }

    @PostMapping("/{accountId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPlan(@PathVariable Long accountId, @RequestBody @Valid PlanBody body) {
        if (body.amountCents() <= 0) {
            throw new IllegalArgumentException("The contribution amount must be more than zero.");
        }
        String symbol = body.symbol().trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(props.zoneId());
        LocalDate asOf = body.asOf() == null ? today : body.asOf();
        Double startShares = body.startShares();
        if (startShares == null && body.startBalanceCents() != null) {
            NavigableMap<LocalDate, Double> closes = quotes.history(symbol, asOf.minusDays(7), asOf);
            if (closes.isEmpty()) {
                throw new ProviderException("No price for " + symbol + " around " + asOf, false);
            }
            startShares = (body.startBalanceCents() / 100.0) / closes.lastEntry().getValue();
        }
        QuoteClient.Quote quote = quotes.quote(symbol);
        Double shares = startShares;
        Long id = tx.execute(s -> {
            Account a = accounts.findById(accountId).orElseThrow();
            if (shares != null) {
                Holding h = portfolio.setShares(a, symbol, shares, asOf, TradeSource.ADJUSTMENT);
                h.setName(quote.name());
            }
            RecurringBuy plan = new RecurringBuy(a, symbol, body.amountCents(), body.firstContribution());
            plan.setCadence(body.cadence());
            if (body.intervalDays() != null) {
                plan.setIntervalDays(body.intervalDays());
            }
            return recurring.save(plan).getId();
        });
        portfolio.refreshAll();
        if (shares != null) {
            portfolio.backfill(accountId, asOf);
        }
        return Map.of("id", id);
    }

    public record PlanPatch(Long amountCents, Boolean active) {
    }

    @PutMapping("/plans/{id}")
    public Map<String, Object> updatePlan(@PathVariable Long id, @RequestBody PlanPatch body) {
        tx.executeWithoutResult(s -> {
            RecurringBuy p = recurring.findById(id).orElseThrow();
            if (body.amountCents() != null && body.amountCents() > 0) {
                p.setAmountCents(body.amountCents());
            }
            if (body.active() != null) {
                p.setActive(body.active());
            }
        });
        return Map.of("ok", true);
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable Long id) {
        tx.executeWithoutResult(s -> recurring.deleteById(id));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        portfolio.applyRecurring();
        List<String> failed = portfolio.refreshPrices();
        return Map.of("ok", failed.isEmpty(), "failed", failed);
    }
}
