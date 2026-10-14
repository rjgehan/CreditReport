package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Looking back: a score for each month, a recap of any month, and the daily
 * net worth moves behind the calendar.
 *
 * <p>The score is out of 100 and deliberately simple enough to explain on one
 * screen. Kept (40): take-home minus spending against your savings target.
 * Targets (30): spending targets kept. Net worth (20): did it go up. Steady (10):
 * spending against your own recent average.
 */
@Service
@Transactional(readOnly = true)
public class HistoryService {

    static final int KEPT_POINTS = 40;
    static final int TARGET_POINTS = 30;
    static final int NET_WORTH_POINTS = 20;
    static final int STEADY_POINTS = 10;
    /** Without a savings target, keeping a fifth of take-home counts as full marks. */
    static final double DEFAULT_KEEP_SHARE = 0.20;

    private final FlowService flow;
    private final Budget budget;
    private final TargetService targets;
    private final NetWorthService netWorth;
    private final Settings settings;
    private final TransactionRepository txns;
    private final ZoneId zone;

    public HistoryService(FlowService flow, Budget budget, TargetService targets, NetWorthService netWorth,
            Settings settings, TransactionRepository txns, AppProperties props) {
        this.flow = flow;
        this.budget = budget;
        this.targets = targets;
        this.netWorth = netWorth;
        this.settings = settings;
        this.txns = txns;
        this.zone = props.zoneId();
    }

    public record Part(String name, int points, int outOf, String detail) {
    }

    /**
     * @param keptCents take-home minus spending
     * @param netWorthChangePercent from the end of the previous month to the end of this one (or today)
     * @param leftToSpendCents the current month only: what is still spendable after the savings target
     * @param covered the whole month is inside the transaction history (the first month usually isn't)
     */
    public record MonthScore(String month, boolean current, boolean covered, long incomeCents, long spentCents, long keptCents,
            Long savingsTargetCents, Double savingsRate, Long netWorthStartCents, Long netWorthEndCents,
            Double netWorthChangePercent, Long leftToSpendCents, int score, String grade, List<Part> parts) {
    }

    public List<MonthScore> months(int count) {
        YearMonth now = YearMonth.now(zone);
        YearMonth first = now.minusMonths(count - 1);
        // Three extra months back so the oldest month's "steady" comparison has an average.
        Map<YearMonth, FlowService.Breakdown> breakdowns = new HashMap<>();
        for (YearMonth m = first.minusMonths(3); !m.isAfter(now); m = m.plusMonths(1)) {
            breakdowns.put(m, flow.month(m));
        }
        NavigableMap<LocalDate, Long> worth = netWorth.daily(first.atDay(1).minusDays(1), LocalDate.now(zone));
        List<MonthScore> out = new ArrayList<>();
        // Months that ended before the bank history began hold only what you typed in (old
        // rent on the Bills page, say): no income, nothing to score.
        LocalDate historyStart = txns.earliestDay().orElse(null);
        for (YearMonth m = now; !m.isBefore(first); m = m.minusMonths(1)) {
            if (historyStart != null && m.atEndOfMonth().isBefore(historyStart)) {
                continue;
            }
            MonthScore s = score(m, breakdowns, worth);
            if (s.incomeCents() > 0 || s.spentCents() > 0) {
                out.add(s);
            }
        }
        return out;
    }

    MonthScore score(YearMonth m, Map<YearMonth, FlowService.Breakdown> breakdowns, NavigableMap<LocalDate, Long> worth) {
        FlowService.Breakdown b = breakdowns.computeIfAbsent(m, flow::month);
        boolean current = YearMonth.now(zone).equals(m);
        Budget.MonthPlan plan = budget.plan(m, b);
        long income = b.cashIncome();
        long spent = b.spent();
        long kept = income - spent;
        Long target = settings.savingsTargetCents();
        List<Part> parts = new ArrayList<>();

        // Kept: what's left of take-home after spending, against the target. Mid-month,
        // rent on the 1st would make "kept so far" look awful, so the current month
        // asks the forward-looking question: with the income still expected, and if
        // nothing else were spent, how much of the target could be kept?
        long basis = current ? plan.incomeCents() : income;
        long canKeep = basis - spent;
        long goal = target != null ? target : Math.round(basis * DEFAULT_KEEP_SHARE);
        double keptRatio = goal <= 0 ? (canKeep >= 0 ? 1 : 0) : clamp((double) canKeep / goal);
        String goalName = target != null ? "savings target" : "a fifth of take-home (set a target to use yours)";
        String keptDetail;
        if (current) {
            keptDetail = keptRatio >= 1 ? "On track to keep your " + goalName
                    : canKeep <= 0 ? "Nothing left to keep this month"
                    : String.format("Can keep %.0f%% of your %s", keptRatio * 100, goalName);
        } else {
            keptDetail = keptRatio >= 1 ? "Kept your " + goalName
                    : String.format("Kept %.0f%% of your %s", keptRatio * 100, goalName);
        }
        parts.add(new Part("Kept", (int) Math.round(KEPT_POINTS * keptRatio), KEPT_POINTS, keptDetail));

        // Targets: how many category caps held.
        TargetService.Progress progress = targets.progress(m);
        double targetRatio;
        String targetDetail;
        if (!progress.spending().isEmpty()) {
            long held = progress.spending().stream().filter(s -> s.spentCents() <= s.limitCents()).count();
            targetRatio = (double) held / progress.spending().size();
            targetDetail = held + " of " + progress.spending().size() + " spending targets held";
        } else if (plan.spendableCents() > 0) {
            targetRatio = clamp(2 - (double) spent / plan.spendableCents());
            targetDetail = "Spending against what was spendable";
        } else {
            targetRatio = 0;
            targetDetail = "No income to spend against";
        }
        parts.add(new Part("Targets", (int) Math.round(TARGET_POINTS * targetRatio), TARGET_POINTS, targetDetail));

        // Net worth: up is full marks, down 2% or more is none.
        Long start = valueOn(worth, m.atDay(1).minusDays(1));
        Long end = valueOn(worth, current ? LocalDate.now(zone) : m.atEndOfMonth());
        Double changePct = start == null || end == null || start == 0 ? null : 100.0 * (end - start) / Math.abs(start);
        double worthRatio = changePct == null ? 0.5 : clamp(1 + changePct / 2);
        parts.add(new Part("Net worth", (int) Math.round(NET_WORTH_POINTS * worthRatio), NET_WORTH_POINTS,
                changePct == null ? "Not enough balance history yet" : String.format("%+.1f%% over the month", changePct)));

        // Steady: spending other than fixed costs (rent, loans) against the three
        // months before. Mid-month the comparison
        // is like for like: the 1st to today now, against the 1st to the same day then.
        long prior = 0;
        int priorCount = 0;
        for (int i = 1; i <= 3; i++) {
            YearMonth p = m.minusMonths(i);
            if (!budget.covered(p)) {
                continue;
            }
            long pSpent = current ? budget.variableSpentThrough(p, plan.dayOfMonth())
                    : breakdowns.computeIfAbsent(p, flow::month).variableSpent();
            if (pSpent > 0) {
                prior += pSpent;
                priorCount++;
            }
        }
        double steadyRatio;
        String steadyDetail;
        if (priorCount == 0) {
            steadyRatio = 1;
            steadyDetail = "No earlier months to compare with";
        } else {
            double ratio = b.variableSpent() / ((double) prior / priorCount);
            steadyRatio = clamp((1.25 - ratio) / 0.25);
            steadyDetail = String.format("%s%.0f%% vs your last 3 months%s", ratio >= 1 ? "+" : "−",
                    Math.abs(ratio - 1) * 100, current ? " at this point" : "");
        }
        parts.add(new Part("Steady", (int) Math.round(STEADY_POINTS * steadyRatio), STEADY_POINTS, steadyDetail));

        int score = parts.stream().mapToInt(Part::points).sum();
        return new MonthScore(m.toString(), current, budget.covered(m), income, spent, kept, target,
                income > 0 ? (double) kept / income : null, start, end, changePct,
                current ? plan.leftToSpendCents() : null, score, grade(score), parts);
    }

    static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static Long valueOn(NavigableMap<LocalDate, Long> worth, LocalDate day) {
        Map.Entry<LocalDate, Long> e = worth.floorEntry(day);
        return e == null ? null : e.getValue();
    }

    // --- Recap ------------------------------------------------------------------------

    public record CategoryChange(Long categoryId, String name, long cents, long previousCents) {
    }

    /** @param empty nothing came in or went out that month, so there is nothing to score */
    public record Recap(MonthScore score, Budget.MonthPlan plan, List<CategoryChange> categories,
            TargetService.Progress targets, boolean empty) {
    }

    public Recap recap(YearMonth m) {
        Map<YearMonth, FlowService.Breakdown> breakdowns = new HashMap<>();
        NavigableMap<LocalDate, Long> worth = netWorth.daily(m.atDay(1).minusDays(1), m.atEndOfMonth());
        MonthScore score = score(m, breakdowns, worth);
        FlowService.Breakdown b = breakdowns.get(m);
        // Like for like: a month in progress is compared with the same days of the month before.
        YearMonth p = m.minusMonths(1);
        FlowService.Breakdown previous = score.current()
                ? flow.compute(p.atDay(1), p.atDay(Math.min(budget.plan(m, b).dayOfMonth(), p.lengthOfMonth())))
                : breakdowns.computeIfAbsent(p, flow::month);
        Map<Long, Long> before = new HashMap<>();
        previous.expenses().forEach((k, v) -> before.put(k.categoryId(), v));
        // Rent and loans are the same every month; the list is about what moved.
        List<CategoryChange> categories = b.expenses().entrySet().stream().filter(e -> !e.getKey().fixed()).limit(5)
                .map(e -> new CategoryChange(e.getKey().categoryId(), e.getKey().name(), e.getValue(),
                        before.getOrDefault(e.getKey().categoryId(), 0L)))
                .toList();
        return new Recap(score, budget.plan(m, b), categories, targets.progress(m),
                score.incomeCents() == 0 && score.spentCents() == 0);
    }

    // --- Calendar -----------------------------------------------------------------------

    /**
     * @param changePercent net worth change from the day before
     * @param spentCents spending that day (refunds can bring it to zero, never below); incomeCents the same for income
     */
    public record Day(LocalDate day, Long netWorthCents, Double changePercent, long spentCents, long incomeCents) {
    }

    /** @param changePercent from the end of the month before to the end of this one (or today) */
    public record CalendarMonth(String month, Double changePercent, List<Day> days) {
    }

    public CalendarMonth calendar(YearMonth m) {
        List<Day> days = days(m);
        NavigableMap<LocalDate, Long> worth = netWorth.daily(m.atDay(1).minusDays(1), m.atEndOfMonth());
        Long start = worth.get(m.atDay(1).minusDays(1));
        Map.Entry<LocalDate, Long> end = worth.lastEntry();
        Double change = start == null || start == 0 || end == null ? null : 100.0 * (end.getValue() - start) / Math.abs(start);
        return new CalendarMonth(m.toString(), change, days);
    }

    public List<Day> days(YearMonth m) {
        LocalDate first = m.atDay(1);
        LocalDate last = m.atEndOfMonth();
        NavigableMap<LocalDate, Long> worth = netWorth.daily(first.minusDays(1), last);
        Map<LocalDate, long[]> flows = new HashMap<>();
        for (Transaction t : txns.findVisibleBetween(first, last)) {
            FlowService.Bucket bucket = FlowService.bucket(t);
            long[] f = flows.computeIfAbsent(t.getDay(), d -> new long[2]);
            if (bucket.kind() == CategoryKind.EXPENSE) {
                f[0] -= t.getAmountCents();
            } else if (bucket.kind() == CategoryKind.INCOME && bucket.earned()) {
                f[1] += t.getAmountCents();
            }
        }
        LocalDate today = LocalDate.now(zone);
        List<Day> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (d.isAfter(today)) {
                out.add(new Day(d, null, null, 0, 0));
                continue;
            }
            Long value = worth.get(d);
            Long before = worth.get(d.minusDays(1));
            Double change = value == null || before == null || before == 0 ? null : 100.0 * (value - before) / Math.abs(before);
            long[] f = flows.getOrDefault(d, new long[2]);
            out.add(new Day(d, value, change, Math.max(0, f[0]), Math.max(0, f[1])));
        }
        return out;
    }
}
