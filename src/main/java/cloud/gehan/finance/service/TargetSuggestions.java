package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.GoalType;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.SpendingTarget;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.SpendingTargetRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Spending targets, a savings target and an emergency fund, worked out so nobody
 * has to type them in. Each category's target starts from what you've actually spent
 * in your last few complete months; categories with no history yet start from what a
 * single person without a car typically spends in Hoboken. A fifth of take-home pay
 * goes to savings (the 50/30/20 rule of thumb), and if everything doesn't fit, the
 * wants (dining out, shopping and so on) shrink before the savings target does.
 */
@Service
@Transactional(readOnly = true)
public class TargetSuggestions {

    /** Complete months averaged over. */
    static final int MONTHS = 3;
    /** A category spending less than this a month isn't worth a target. */
    static final long MIN_AVERAGE_CENTS = 20_00;
    static final double SAVINGS_SHARE = 0.20;
    /** Wants never shrink below this share of what you actually spend on them. */
    static final double FLOOR = 0.5;

    /** Needs: targets here hold steady rather than shrink. */
    static final Set<String> ESSENTIALS = Set.of("Groceries", "Utilities", "Health", "Insurance", "Transportation",
            "Education", "Gas");
    /** Never targeted: catch-alls, refunds and things you can't budget down. */
    static final Set<String> SKIP = Set.of(ReferenceData.UNCATEGORIZED, ReferenceData.REFUNDS, "Fees & Interest",
            "Taxes");
    /** A starting point where there's no history yet: single, no car, Hoboken. */
    static final Map<String, Long> TYPICAL = Map.of(
            "Groceries", 400_00L, "Dining", 350_00L, "Transportation", 120_00L, "Utilities", 150_00L,
            "Shopping", 200_00L, "Subscriptions", 50_00L, "Entertainment", 100_00L, "Personal Care", 50_00L,
            "Health", 60_00L);

    private final Budget budget;
    private final FlowService flow;
    private final CategoryRepository categories;
    private final SpendingTargetRepository targets;
    private final SavingsGoalRepository goals;
    private final AccountRepository accounts;
    private final Settings settings;
    private final TransactionRepository txns;
    private final ZoneId zone;

    public TargetSuggestions(Budget budget, FlowService flow, CategoryRepository categories,
            SpendingTargetRepository targets, SavingsGoalRepository goals, AccountRepository accounts,
            Settings settings, TransactionRepository txns, AppProperties props) {
        this.budget = budget;
        this.flow = flow;
        this.categories = categories;
        this.targets = targets;
        this.goals = goals;
        this.accounts = accounts;
        this.settings = settings;
        this.txns = txns;
        this.zone = props.zoneId();
    }

    /**
     * @param averageCents what you spent a month lately, or null when there's no history
     * @param existing a target is already set; it's left alone
     */
    public record Target(Long categoryId, String name, String icon, long limitCents, Long averageCents,
            boolean essential, boolean existing) {
    }

    public record Goal(String name, GoalType type, long targetCents, Long accountId, String accountName,
            LocalDate targetDate) {
    }

    /**
     * @param incomeCents take-home a month, or null when no pay has been recognised yet
     * @param billsCents rent, loans and the other monthly bills, or null with no history to know them from
     * @param otherCents spending no target covers (Venmo and Zelle waiting to be sorted, fees, small
     *        categories): it still comes out of the month, so the plan leaves room for it
     * @param savingsTargetCents yours if you've set one, otherwise the suggestion; null without an income
     * @param savingsTrimmed the suggestion is less than a fifth of take-home because the rest didn't fit
     * @param unplannedCents what's left after all of it; negative if it doesn't fit
     * @param months how many complete months the averages come from (0: typical amounts only)
     * @param goal null when you already have a balance goal, or there's nothing to base one on
     */
    public record Suggestion(Long incomeCents, Long billsCents, long otherCents, Long savingsTargetCents,
            boolean savingsTargetSet, boolean savingsTrimmed, List<Target> targets, Goal goal, long unplannedCents,
            int months) {
    }

    public Suggestion suggest() {
        YearMonth now = YearMonth.now(zone);
        // Only months the bank history fully covers: one that starts on the 3rd misses rent paid on the 1st.
        LocalDate historyStart = txns.earliestDay().orElse(null);
        List<YearMonth> history = new ArrayList<>();
        for (int i = 1; i <= 12 && history.size() < MONTHS && historyStart != null; i++) {
            YearMonth m = now.minusMonths(i);
            if (!m.atDay(1).isBefore(historyStart)) {
                history.add(m);
            }
        }
        Map<Long, Long> spent = new HashMap<>();
        long allSpent = 0;
        for (YearMonth m : history) {
            FlowService.Breakdown b = flow.month(m);
            allSpent += b.spent();
            b.expenses().forEach((bucket, cents) -> {
                if (bucket.categoryId() != null) {
                    spent.merge(bucket.categoryId(), cents, Long::sum);
                }
            });
        }
        int n = history.size();
        Map<Long, SpendingTarget> mine = new HashMap<>();
        for (SpendingTarget t : targets.findAll()) {
            mine.put(t.getCategory().getId(), t);
        }

        long bills = 0;
        long covered = 0; // average spending the bills and targets account for
        List<Target> needs = new ArrayList<>();
        List<Target> wants = new ArrayList<>();
        for (Category c : categories.findAllByOrderBySortOrderAscNameAsc()) {
            if (c.getKind() != CategoryKind.EXPENSE) {
                continue;
            }
            Long average = n == 0 ? null : spent.getOrDefault(c.getId(), 0L) / n;
            if (c.isFixedCost()) {
                bills += average == null ? 0 : average;
                covered += average == null ? 0 : average;
                continue;
            }
            Long limit = null;
            boolean existing = mine.containsKey(c.getId());
            if (existing) {
                // Yours, whatever the history says: listed and counted, never changed.
                limit = mine.get(c.getId()).getMonthlyLimitCents();
            } else if (SKIP.contains(c.getName())) {
                continue;
            } else if (average != null && average >= MIN_AVERAGE_CENTS) {
                limit = roundUp(average);
            } else if (n == 0 && TYPICAL.containsKey(c.getName())) {
                limit = TYPICAL.get(c.getName());
            }
            if (limit == null) {
                continue;
            }
            covered += average == null ? 0 : average;
            Target t = new Target(c.getId(), c.getName(), c.getIcon(), limit, average, ESSENTIALS.contains(c.getName()),
                    existing);
            (t.essential() ? needs : wants).add(t);
        }
        long other = n == 0 ? 0 : Math.max(0, allSpent / n - covered);

        Long income = budget.expectedIncome(now);
        Long yours = settings.savingsTargetCents();
        Long savings = yours != null ? yours : income == null ? null : roundDown((long) (income * SAVINGS_SHARE), 50_00);
        boolean trimmed = false;
        long needsTotal = needs.stream().mapToLong(Target::limitCents).sum();
        long wantsTotal = wants.stream().mapToLong(Target::limitCents).sum();
        if (income != null) {
            long room = income - bills - other - savings - needsTotal;
            if (wantsTotal > room) {
                // Wants give way first, down to half of what you spend on them, and only
                // the ones you haven't set yourself.
                long fixed = wants.stream().filter(Target::existing).mapToLong(Target::limitCents).sum();
                long flexible = wantsTotal - fixed;
                double factor = flexible <= 0 ? 1 : Math.max(FLOOR, (double) (room - fixed) / flexible);
                List<Target> shrunk = new ArrayList<>();
                for (Target t : wants) {
                    shrunk.add(t.existing() ? t : new Target(t.categoryId(), t.name(), t.icon(),
                            Math.max(25_00, roundDown((long) (t.limitCents() * factor), 25_00)), t.averageCents(),
                            false, false));
                }
                wants = shrunk;
                wantsTotal = wants.stream().mapToLong(Target::limitCents).sum();
                // Still too much: the suggested savings target comes down to what's really left.
                // One you set yourself is your call and stays.
                if (yours == null) {
                    long fits = Math.max(0, roundDown(Math.min(savings, income - bills - other - needsTotal - wantsTotal), 50_00));
                    trimmed = fits < savings;
                    savings = fits;
                }
            }
        }
        List<Target> all = new ArrayList<>(needs);
        all.addAll(wants);
        all.sort(Comparator.comparingLong(Target::limitCents).reversed());
        long unplanned = income == null ? 0 : income - bills - other - savings - needsTotal - wantsTotal;

        Goal goal = n == 0 ? null : emergencyFund(bills + needsTotal, savings, now);
        return new Suggestion(income, n == 0 ? null : bills, other, savings, yours != null, trimmed, all, goal,
                unplanned, n);
    }

    /**
     * Three months of the things that can't stop if pay does (bills and needs), kept in
     * your savings account, by when the savings target gets you there (six months to three
     * years). None if you already have a balance goal or there's no savings account to hold it.
     */
    private Goal emergencyFund(long essentialsPerMonth, Long savingsPerMonth, YearMonth now) {
        if (essentialsPerMonth <= 0 || goals.findAll().stream().anyMatch(g -> g.getType() == GoalType.BALANCE)) {
            return null;
        }
        Account account = accounts.findByHiddenFalse().stream()
                .filter(a -> a.getType() == AccountType.SAVINGS)
                .max(Comparator.comparingLong(Account::getBalanceCents)).orElse(null);
        if (account == null) {
            return null;
        }
        long target = roundUp(essentialsPerMonth * 3, 500_00);
        long toGo = Math.max(0, target - account.getBalanceCents());
        int months = savingsPerMonth == null || savingsPerMonth <= 0 ? 24
                : (int) Math.min(36, Math.max(6, (toGo + savingsPerMonth - 1) / savingsPerMonth));
        return new Goal("Emergency fund", GoalType.BALANCE, target, account.getId(), account.getName(),
                now.plusMonths(months).atEndOfMonth());
    }

    public record Applied(int targets, boolean savingsTarget, boolean goal) {
    }

    /**
     * Saves what {@link #suggest()} works out right now: targets for categories without one,
     * the savings target if none is set, and the emergency fund. What you set yourself stays.
     */
    @Transactional
    public Applied apply() {
        Suggestion s = suggest();
        int made = 0;
        for (Target t : s.targets()) {
            if (!t.existing()) {
                Category c = categories.findById(t.categoryId()).orElseThrow();
                targets.save(new SpendingTarget(c, t.limitCents()));
                made++;
            }
        }
        boolean saving = !s.savingsTargetSet() && s.savingsTargetCents() != null && s.savingsTargetCents() > 0;
        // (A trimmed-to-nothing suggestion isn't a target; the preview says so.)
        if (saving) {
            settings.setSavingsTargetCents(s.savingsTargetCents());
        }
        if (s.goal() != null) {
            SavingsGoal g = new SavingsGoal(s.goal().name(), s.goal().type());
            g.setTargetCents(s.goal().targetCents());
            g.setAccount(s.goal().accountId() == null ? null : accounts.findById(s.goal().accountId()).orElseThrow());
            g.setTargetDate(s.goal().targetDate());
            goals.save(g);
        }
        return new Applied(made, saving, s.goal() != null);
    }

    static long roundUp(long cents) {
        return roundUp(cents, 25_00);
    }

    static long roundUp(long cents, long step) {
        return (cents + step - 1) / step * step;
    }

    static long roundDown(long cents, long step) {
        return Math.max(0, cents / step * step);
    }
}
