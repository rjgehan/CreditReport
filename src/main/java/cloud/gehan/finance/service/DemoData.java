package cloud.gehan.finance.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.BuyCadence;
import cloud.gehan.finance.model.GoalType;
import cloud.gehan.finance.model.PayFrequency;
import cloud.gehan.finance.model.Paystub;
import cloud.gehan.finance.model.RecurringBuy;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.SpendingTarget;
import cloud.gehan.finance.model.TradeSource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.PaystubRepository;
import cloud.gehan.finance.repo.RecurringBuyRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.SpendingTargetRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Six months of made-up but realistic money, for trying the app before any real
 * account is connected (APP_DEMO_DATA=true). Raw descriptions go through the
 * same categorizer and transfer matcher as synced data, so the demo exercises
 * the real pipeline rather than a pre-labelled fixture.
 */
@Component
@Order(10)
public class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final AppProperties props;
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final SpendingTargetRepository targets;
    private final SavingsGoalRepository goals;
    private final PaystubRepository paystubs;
    private final Balances balances;
    private final Categorizer categorizer;
    private final TransferMatcher matcher;
    private final PortfolioService portfolio;
    private final RecurringBuyRepository recurring;
    private final Settings settings;
    private final TransactionTemplate tx;

    public DemoData(AppProperties props, AccountRepository accounts, TransactionRepository txns,
            CategoryRepository categories, SpendingTargetRepository targets, SavingsGoalRepository goals,
            PaystubRepository paystubs, Balances balances, Categorizer categorizer, TransferMatcher matcher,
            PortfolioService portfolio, RecurringBuyRepository recurring, Settings settings,
            TransactionTemplate tx) {
        this.props = props;
        this.accounts = accounts;
        this.txns = txns;
        this.categories = categories;
        this.targets = targets;
        this.goals = goals;
        this.paystubs = paystubs;
        this.balances = balances;
        this.categorizer = categorizer;
        this.matcher = matcher;
        this.portfolio = portfolio;
        this.recurring = recurring;
        this.settings = settings;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.demoData() && accounts.count() == 0) {
            tx.executeWithoutResult(s -> seed(LocalDate.now(props.zoneId())));
        }
    }

    private record Event(Account account, LocalDate day, long cents, String description) {
    }

    void seed(LocalDate today) {
        Random r = new Random(42);
        Account checking = account("Checking", "SoFi", "4410", AccountType.CHECKING, null);
        Account savings = account("Savings", "SoFi", "4321", AccountType.SAVINGS, null);
        Account sapphire = account("Freedom Unlimited", "Chase", "8812", AccountType.CREDIT_CARD, 15_000_00L);
        Account amex = account("Quicksilver", "Capital One", "1007", AccountType.CREDIT_CARD, 8_000_00L);
        Account webull = account("Individual Brokerage", "Webull", "6650", AccountType.BROKERAGE, null);
        Account pcs = account("401(k) Plan", "PCS Retirement", null, AccountType.RETIREMENT, null);

        LocalDate start = today.minusMonths(6).withDayOfMonth(1);
        List<Event> events = new ArrayList<>();

        // Paychecks every other Friday.
        for (LocalDate d = start.with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY)); !d.isAfter(today); d = d.plusWeeks(2)) {
            events.add(new Event(checking, d, 2_846_12, "ACME CORP PAYROLL PPD ID: 9876543210"));
        }

        for (LocalDate m = start; !m.isAfter(today); m = m.plusMonths(1)) {
            int len = m.lengthOfMonth();
            monthly(events, today, checking, m, 1, -1_950_00, "AVALON APARTMENTS RENT");
            monthly(events, today, checking, m, 2, -500_00, "ONLINE TRANSFER TO SAV XXXXXX4321");
            monthly(events, today, savings, m, 3, 500_00, "TRANSFER FROM CHK XXXXXX4410");
            monthly(events, today, sapphire, m, 5, -38_00, "LEMONADE INSURANCE RENTERS");
            monthly(events, today, checking, m, 12, -(88_00 + r.nextInt(55_00)), "PSE&G CO PAYMENT");
            monthly(events, today, sapphire, m, 18, -85_00, "VERIZON WIRELESS");
            monthly(events, today, checking, m, 22, -79_99, "COMCAST XFINITY");
            monthly(events, today, sapphire, m, 9, -15_49, "NETFLIX.COM");
            monthly(events, today, amex, m, 14, -11_99, "SPOTIFY USA");
            monthly(events, today, amex, m, 3, -2_99, "APPLE.COM/BILL ICLOUD");
            monthly(events, today, sapphire, m, 20, -20_00, "OPENAI *CHATGPT SUBSCR");
            monthly(events, today, checking, m, 7, -24_99, "PLANET FITNESS CLUB FEES");
            monthly(events, today, checking, m, 16, -300_00, "WEBULL FINANCIAL ACH DEBIT");
            monthly(events, today, webull, m, 17, 300_00, "ACH DEPOSIT FROM BANK");
            monthly(events, today, checking, m, len, 38_00 + r.nextInt(8_00), "INTEREST PAID");
            if (r.nextBoolean()) {
                monthly(events, today, checking, m, 8 + r.nextInt(15), -(20_00 + r.nextInt(60_00)), "VENMO PAYMENT 1023");
            }
            monthly(events, today, amex, m, 4 + r.nextInt(20), -(18_00 + r.nextInt(30_00)), "CVS/PHARMACY #0123");

            for (int day = 1; day <= len; day++) {
                LocalDate d = m.withDayOfMonth(day);
                if (d.isAfter(today)) {
                    break;
                }
                if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
                    String[] stores = {"WHOLE FOODS MARKET #10233", "TRADER JOE'S #512", "WEGMANS #87"};
                    events.add(new Event(amex, d, -(85_00 + r.nextInt(105_00)), stores[r.nextInt(stores.length)]));
                }
                if (r.nextInt(7) < 3) {
                    String[] places = {"STARBUCKS STORE 22451", "CHIPOTLE 1932", "DOORDASH*THAI BASIL",
                            "TST* LOCAL BISTRO", "SWEETGREEN HOBOKEN", "DUNKIN #348812"};
                    events.add(new Event(sapphire, d, -(6_00 + r.nextInt(58_00)), places[r.nextInt(places.length)]));
                }
                if (r.nextInt(8) == 0) {
                    events.add(new Event(amex, d, -(15_00 + r.nextInt(105_00)), "AMAZON.COM*2K4L1 AMZN.COM/BILL"));
                }
                if (r.nextInt(25) == 0) {
                    events.add(new Event(sapphire, d, -(14_00 + r.nextInt(30_00)), "UBER *TRIP"));
                }
                if (r.nextInt(30) == 0) {
                    events.add(new Event(sapphire, d, -(40_00 + r.nextInt(120_00)), "TICKETMASTER"));
                }
                if (r.nextInt(28) == 0) {
                    events.add(new Event(amex, d, -(25_00 + r.nextInt(90_00)), "TARGET 00012345"));
                }
            }
        }
        // One trip, one refund.
        LocalDate trip = today.minusMonths(3).withDayOfMonth(10);
        events.add(new Event(sapphire, trip, -412_60, "DELTA AIR LINES 0062312345"));
        events.add(new Event(sapphire, trip.plusDays(12), -689_14, "MARRIOTT BOSTON COPLEY"));
        events.add(new Event(amex, today.minusMonths(1).withDayOfMonth(19), 34_99, "AMAZON.COM REFUND"));

        // Cards are paid in full: Sapphire on the 25th, Amex on the 27th, each
        // covering everything charged since the last payment.
        events.sort((a, b) -> a.day().compareTo(b.day()));
        events = withCardPayments(events, checking, sapphire, 25, "CHASE CREDIT CRD AUTOPAY", "AUTOMATIC PAYMENT - THANK YOU", today);
        events = withCardPayments(events, checking, amex, 27, "CAPITAL ONE MOBILE PMT", "PAYMENT RECEIVED - THANK YOU", today);

        Map<Account, Long> balance = new HashMap<>();
        balance.put(checking, 4_200_00L);
        balance.put(savings, 12_500_00L);
        balance.put(sapphire, 0L);
        balance.put(amex, 0L);

        List<Transaction> created = new ArrayList<>();
        int n = 0;
        int e = 0;
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            while (e < events.size() && events.get(e).day().equals(d)) {
                Event ev = events.get(e++);
                created.add(txns.save(new Transaction(ev.account(), "demo-" + (n++), ev.day(), ev.cents(), ev.description())));
                balance.merge(ev.account(), ev.cents(), Long::sum);
            }
            for (Map.Entry<Account, Long> b : balance.entrySet()) {
                if (b.getKey() != webull) {
                    balances.record(b.getKey(), d, b.getValue(), d.equals(today));
                }
            }
        }

        categorizer.categorize(created);
        matcher.match(created, categorizer.context());

        // The investment accounts are valued from holdings at real market prices,
        // which PortfolioService fetches (and back-fills history for) once the app
        // is up: Webull as a few positions, PCS as a contribution every payday.
        webull.setCashCents(212_40L);
        LocalDate opened = start.minusDays(1);
        portfolio.setShares(webull, "VTI", 18.5, opened, TradeSource.ADJUSTMENT);
        portfolio.setShares(webull, "AAPL", 12, opened, TradeSource.ADJUSTMENT);
        portfolio.setShares(webull, "SCHD", 40, opened, TradeSource.ADJUSTMENT);
        portfolio.setShares(pcs, "VOO", 58.2, opened, TradeSource.ADJUSTMENT);
        RecurringBuy contributions = new RecurringBuy(pcs, "VOO", 255_00 + 127_50,
                start.with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY)));
        contributions.setCadence(BuyCadence.EVERY_N_DAYS);
        contributions.setIntervalDays(14);
        recurring.save(contributions);

        Paystub stub = new Paystub("Acme Corp");
        stub.setMatchPattern("ACME CORP PAYROLL");
        stub.setFrequency(PayFrequency.BIWEEKLY);
        stub.setGrossCents(4_250_00);
        stub.setFederalTaxCents(520_40);
        stub.setStateTaxCents(210_55);
        stub.setFicaCents(325_13);
        stub.setRetirementCents(255_00);
        stub.setBenefitsCents(92_80);
        stub.setEmployerMatchCents(127_50);
        paystubs.save(stub);

        target("Dining", 400_00);
        target("Groceries", 650_00);
        target("Shopping", 300_00);
        target("Subscriptions", 60_00);
        target("Entertainment", 100_00);

        SavingsGoal emergency = new SavingsGoal("Emergency fund", GoalType.BALANCE);
        emergency.setTargetCents(20_000_00L);
        emergency.setAccount(savings);
        emergency.setTargetDate(today.plusMonths(14).withDayOfMonth(1));
        goals.save(emergency);
        SavingsGoal invest = new SavingsGoal("Invest every month", GoalType.MONTHLY_AMOUNT);
        invest.setTargetCents(300_00L);
        invest.setCategory(categories.findByNameIgnoreCase(ReferenceData.INVESTING).orElse(null));
        goals.save(invest);
        SavingsGoal rate = new SavingsGoal("Save a fifth of income", GoalType.SAVINGS_RATE);
        rate.setTargetPercent(20.0);
        goals.save(rate);

        settings.setSavingsTargetCents(1_000_00L);
        log.info("Seeded demo data: 6 accounts, {} transactions", created.size());
    }

    private Account account(String name, String institution, String mask, AccountType type, Long limit) {
        Account a = new Account(null, "demo-" + name, name, type);
        a.setInstitution(institution);
        a.setMask(mask);
        a.setCreditLimitCents(limit);
        return accounts.save(a);
    }

    private static void monthly(List<Event> events, LocalDate today, Account account, LocalDate month, int day,
            long cents, String description) {
        LocalDate d = month.withDayOfMonth(Math.min(day, month.lengthOfMonth()));
        if (!d.isAfter(today)) {
            events.add(new Event(account, d, cents, description));
        }
    }

    private static List<Event> withCardPayments(List<Event> sorted, Account checking, Account card, int payDay,
            String bankText, String cardText, LocalDate today) {
        List<Event> out = new ArrayList<>(sorted);
        LocalDate lastPaid = LocalDate.MIN;
        for (LocalDate month = sorted.get(0).day().withDayOfMonth(1); !month.isAfter(today); month = month.plusMonths(1)) {
            LocalDate due = month.withDayOfMonth(payDay);
            if (due.isAfter(today)) {
                break;
            }
            long owed = 0;
            for (Event ev : sorted) {
                if (ev.account() == card && !ev.day().isBefore(lastPaid) && ev.day().isBefore(due)) {
                    owed -= ev.cents();
                }
            }
            if (owed > 0) {
                out.add(new Event(checking, due, -owed, bankText));
                out.add(new Event(card, due, owed, cardText));
            }
            lastPaid = due;
        }
        out.sort((a, b) -> a.day().compareTo(b.day()));
        return out;
    }

    private void target(String category, long cents) {
        categories.findByNameIgnoreCase(category).ifPresent(c -> targets.save(new SpendingTarget(c, cents)));
    }
}
