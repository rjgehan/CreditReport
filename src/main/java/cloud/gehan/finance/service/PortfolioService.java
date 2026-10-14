package cloud.gehan.finance.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Holding;
import cloud.gehan.finance.model.HoldingTrade;
import cloud.gehan.finance.model.RecurringBuy;
import cloud.gehan.finance.model.TradeSource;
import cloud.gehan.finance.provider.ProviderException;
import cloud.gehan.finance.provider.QuoteClient;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.BalanceSnapshotRepository;
import cloud.gehan.finance.repo.HoldingRepository;
import cloud.gehan.finance.repo.HoldingTradeRepository;
import cloud.gehan.finance.repo.RecurringBuyRepository;

/**
 * Investment accounts valued from what they hold: Webull from the shares you
 * typed in, PCS from a starting share count plus a contribution every payday.
 *
 * <p>Prices are fetched a few times a trading day, which is plenty for a net
 * worth figure. Network calls happen outside transactions, then the results are
 * written in one short one.
 */
@Service
@Order(20)
public class PortfolioService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    /** How far back a rebuilt balance history goes. */
    static final int MAX_BACKFILL_DAYS = 366;

    private final HoldingRepository holdings;
    private final HoldingTradeRepository trades;
    private final RecurringBuyRepository recurring;
    private final AccountRepository accounts;
    private final BalanceSnapshotRepository snapshots;
    private final QuoteClient quotes;
    private final Balances balances;
    private final TransactionTemplate tx;
    private final ZoneId zone;

    public PortfolioService(HoldingRepository holdings, HoldingTradeRepository trades, RecurringBuyRepository recurring,
            AccountRepository accounts, BalanceSnapshotRepository snapshots, QuoteClient quotes, Balances balances,
            TransactionTemplate tx,
            AppProperties props) {
        this.holdings = holdings;
        this.trades = trades;
        this.recurring = recurring;
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.quotes = quotes;
        this.balances = balances;
        this.tx = tx;
        this.zone = props.zoneId();
    }

    /** Catch up on startup without holding up the web server: contributions due while it was down, fresh prices. */
    @Override
    public void run(ApplicationArguments args) {
        if (holdings.count() == 0 && recurring.count() == 0) {
            return;
        }
        Thread.ofVirtual().name("portfolio-startup").start(this::refreshAll);
    }

    /** Mid-morning, early afternoon and after the close, New York time, on weekdays. */
    @Scheduled(cron = "0 0 10,13 * * MON-FRI", zone = "America/New_York")
    @Scheduled(cron = "0 20 16 * * MON-FRI", zone = "America/New_York")
    public void scheduled() {
        refreshAll();
    }

    public synchronized void refreshAll() {
        try {
            applyRecurring();
        } catch (RuntimeException e) {
            log.warn("Applying recurring contributions failed: {}", e.getMessage());
        }
        refreshPrices();
        try {
            backfillGaps();
        } catch (RuntimeException e) {
            log.warn("Rebuilding balance history failed: {}", e.getMessage());
        }
    }

    /**
     * Holdings entered with a start date in the past (a statement from March,
     * say) leave the net worth chart empty before today. Fill those accounts in.
     */
    void backfillGaps() {
        Map<Long, LocalDate> firstTrade = new HashMap<>();
        for (Holding h : holdings.findAll()) {
            for (HoldingTrade t : trades.findByAccountOrderByDayDescIdDesc(h.getAccount())) {
                firstTrade.merge(h.getAccount().getId(), t.getDay(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        firstTrade.forEach((accountId, first) -> {
            Account account = accounts.findById(accountId).orElse(null);
            if (account == null) {
                return;
            }
            LocalDate firstSnapshot = snapshots.findTopByAccountOrderByDayAsc(account).map(s -> s.getDay()).orElse(null);
            if (firstSnapshot == null || firstSnapshot.isAfter(first.plusDays(7))) {
                backfill(accountId, first);
            }
        });
    }

    /** @return symbols whose price could not be fetched */
    public synchronized List<String> refreshPrices() {
        Set<String> symbols = new TreeSet<>();
        for (Holding h : holdings.findAll()) {
            symbols.add(h.getSymbol());
        }
        Map<String, QuoteClient.Quote> fetched = new HashMap<>();
        List<String> failed = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                fetched.put(symbol, quotes.quote(symbol));
            } catch (ProviderException e) {
                failed.add(symbol);
                log.warn("Price for {} unavailable: {}", symbol, e.getMessage());
            }
        }
        tx.executeWithoutResult(s -> {
            Set<Account> touched = new LinkedHashSet<>();
            for (Holding h : holdings.findAll()) {
                QuoteClient.Quote q = fetched.get(h.getSymbol());
                if (q != null) {
                    h.setLastPrice(q.price());
                    h.setPreviousClose(q.previousClose());
                    h.setPriceUpdatedAt(Instant.now());
                    if (h.getName() == null) {
                        h.setName(q.name());
                    }
                }
                touched.add(h.getAccount());
            }
            for (Account a : touched) {
                revalue(a);
            }
        });
        return failed;
    }

    /** Sets an account's balance from its holdings and cash, and records today's snapshot. Call inside a transaction. */
    public void revalue(Account account) {
        long value = account.getCashCents() == null ? 0 : account.getCashCents();
        for (Holding h : holdings.findByAccount(account)) {
            value += h.valueCents();
        }
        balances.record(account, LocalDate.now(zone), value, true);
    }

    /**
     * Applies every contribution that has come due: shares bought = amount over
     * that day's close (or the last close before it, for a weekend or holiday).
     */
    public void applyRecurring() {
        LocalDate today = LocalDate.now(zone);
        for (RecurringBuy plan : recurring.findByActiveTrue()) {
            List<LocalDate> due = plan.dueThrough(today);
            if (due.isEmpty()) {
                continue;
            }
            NavigableMap<LocalDate, Double> closes = quotes.history(plan.getSymbol(), due.get(0).minusDays(7), today);
            if (closes.isEmpty()) {
                continue;
            }
            LocalDate earliest = due.get(0);
            tx.executeWithoutResult(s -> {
                RecurringBuy p = recurring.findById(plan.getId()).orElseThrow();
                Holding h = holding(p.getAccount(), p.getSymbol());
                for (LocalDate day : due) {
                    var close = closes.floorEntry(day);
                    if (close == null) {
                        continue;
                    }
                    double shares = (p.getAmountCents() / 100.0) / close.getValue();
                    h.setShares(h.getShares() + shares);
                    if (h.getCostBasisCents() != null) {
                        h.setCostBasisCents(h.getCostBasisCents() + p.getAmountCents());
                    }
                    trades.save(new HoldingTrade(p.getAccount(), p.getSymbol(), day, shares, close.getValue(),
                            p.getAmountCents(), TradeSource.RECURRING));
                    p.setLastApplied(day);
                }
                holdings.save(h);
            });
            log.info("Applied {} contribution(s) of {} to {}", due.size(), plan.getSymbol(), plan.getAccount().getName());
            backfill(plan.getAccount().getId(), earliest);
        }
    }

    /**
     * Rebuilds an account's balance history from {@code from} to yesterday by
     * replaying its trades backwards from today's share counts against
     * historical closes. One point per trading week plus every trade day.
     */
    public void backfill(Long accountId, LocalDate from) {
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from.isBefore(today.minusDays(MAX_BACKFILL_DAYS)) ? today.minusDays(MAX_BACKFILL_DAYS) : from;
        if (!start.isBefore(today)) {
            return;
        }
        Account account = accounts.findById(accountId).orElseThrow();
        List<Holding> held = holdings.findByAccount(account);
        Map<String, NavigableMap<LocalDate, Double>> history = new HashMap<>();
        for (Holding h : held) {
            try {
                history.put(h.getSymbol(), quotes.history(h.getSymbol(), start.minusDays(7), today));
            } catch (ProviderException e) {
                log.warn("No history for {}; skipping backfill of {}", h.getSymbol(), account.getName());
                return;
            }
        }
        List<HoldingTrade> log = trades.findByAccountOrderByDayDescIdDesc(account);

        TreeSet<LocalDate> days = new TreeSet<>();
        for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY) {
                days.add(d);
            }
        }
        for (HoldingTrade t : log) {
            if (!t.getDay().isBefore(start) && t.getDay().isBefore(today)) {
                days.add(t.getDay());
            }
        }

        Map<LocalDate, Long> values = new TreeMap<>();
        long cash = account.getCashCents() == null ? 0 : account.getCashCents();
        for (LocalDate day : days) {
            long value = cash;
            boolean anyHeld = false;
            for (Holding h : held) {
                double shares = h.getShares();
                for (HoldingTrade t : log) {
                    if (t.getSymbol().equals(h.getSymbol()) && t.getDay().isAfter(day)) {
                        shares -= t.getShares();
                    }
                }
                var close = history.get(h.getSymbol()).floorEntry(day);
                if (close != null && shares > 1e-9) {
                    value += Math.round(shares * close.getValue() * 100);
                    anyHeld = true;
                }
            }
            // Before tracking began there is nothing to say, and a zero would
            // look like a crash in the net worth chart.
            if (anyHeld) {
                values.put(day, value);
            }
        }
        tx.executeWithoutResult(s -> {
            Account a = accounts.findById(accountId).orElseThrow();
            values.forEach((day, value) -> balances.record(a, day, value, false));
        });
    }

    /** Finds or creates the holding, symbol normalised. Call inside a transaction. */
    public Holding holding(Account account, String symbol) {
        String clean = symbol.trim().toUpperCase(Locale.ROOT);
        return holdings.findByAccountAndSymbol(account, clean).orElseGet(() -> holdings.save(new Holding(account, clean)));
    }

    /**
     * Sets a holding to a share count, logging the difference so history stays
     * replayable. Call inside a transaction.
     */
    public Holding setShares(Account account, String symbol, double shares, LocalDate asOf, TradeSource source) {
        Holding h = holding(account, symbol);
        double delta = shares - h.getShares();
        if (Math.abs(delta) > 1e-9) {
            trades.save(new HoldingTrade(account, h.getSymbol(), asOf, delta, h.getLastPrice(), null, source));
            h.setShares(shares);
        }
        return h;
    }
}
