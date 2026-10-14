package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.CategoryRole;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.SpendingTarget;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.SpendingTargetRepository;

/** How this month is going against the spending caps and savings goals. */
@Service
@Transactional(readOnly = true)
public class TargetService {

    private final SpendingTargetRepository targets;
    private final SavingsGoalRepository goals;
    private final AccountRepository accounts;
    private final FlowService flow;
    private final ZoneId zone;

    public TargetService(SpendingTargetRepository targets, SavingsGoalRepository goals, AccountRepository accounts,
            FlowService flow, AppProperties props) {
        this.targets = targets;
        this.goals = goals;
        this.accounts = accounts;
        this.flow = flow;
        this.zone = props.zoneId();
    }

    /**
     * @param expectedFraction how much of the cap you'd have used by today at an
     *        even pace; null for past months
     * @param status ok, watch (ahead of pace or nearly there), or over
     */
    public record SpendingProgress(Long id, Long categoryId, String name, String icon, long limitCents,
            long spentCents, double fraction, Double expectedFraction, String status) {
    }

    public record GoalProgress(Long id, String name, String type, Long targetCents, Double targetPercent,
            Long accountId, String accountName, Long categoryId, String categoryName, LocalDate targetDate,
            long currentCents, Double currentPercent, double fraction, Long neededPerMonthCents,
            String status, String detail) {
    }

    public record Progress(String month, List<SpendingProgress> spending, List<GoalProgress> goals,
            long budgetedCents, long spentInBudgetsCents) {
    }

    public Progress progress(YearMonth month) {
        FlowService.Breakdown b = flow.month(month);
        LocalDate today = LocalDate.now(zone);
        Double elapsed = YearMonth.from(today).equals(month)
                ? (double) today.getDayOfMonth() / month.lengthOfMonth() : null;

        List<SpendingProgress> spending = new ArrayList<>();
        long budgeted = 0;
        long spentInBudgets = 0;
        for (SpendingTarget t : targets.findAll()) {
            long spent = b.spentIn(t.getCategory().getId());
            long limit = t.getMonthlyLimitCents();
            double fraction = limit <= 0 ? 0 : (double) spent / limit;
            String status;
            if (spent > limit) {
                status = "over";
            } else if (fraction >= 0.9 || (elapsed != null && fraction > elapsed + 0.1)) {
                status = "watch";
            } else {
                status = "ok";
            }
            budgeted += limit;
            spentInBudgets += spent;
            spending.add(new SpendingProgress(t.getId(), t.getCategory().getId(), t.getCategory().getName(),
                    t.getCategory().getIcon(), limit, spent, fraction, elapsed, status));
        }
        spending.sort(Comparator.comparingDouble(SpendingProgress::fraction).reversed());

        List<GoalProgress> goalProgress = new ArrayList<>();
        for (SavingsGoal g : goals.findAll()) {
            goalProgress.add(goal(g, b, today, elapsed));
        }
        return new Progress(month.toString(), spending, goalProgress, budgeted, spentInBudgets);
    }

    private GoalProgress goal(SavingsGoal g, FlowService.Breakdown b, LocalDate today, Double elapsed) {
        Long accountId = g.getAccount() == null ? null : g.getAccount().getId();
        String accountName = g.getAccount() == null ? null : g.getAccount().getName();
        Long categoryId = g.getCategory() == null ? null : g.getCategory().getId();
        String categoryName = g.getCategory() == null ? null : g.getCategory().getName();
        long current = 0;
        Double currentPercent = null;
        double fraction;
        Long needed = null;
        String status;
        String detail;

        switch (g.getType()) {
            case MONTHLY_AMOUNT -> {
                if (categoryId == null) {
                    current = b.saved();
                } else {
                    current = b.savedIn(categoryId);
                    if (g.getCategory().hasRole(CategoryRole.RETIREMENT)) {
                        current += b.savedPreTax();
                    }
                }
                long target = g.getTargetCents() == null ? 0 : g.getTargetCents();
                fraction = target <= 0 ? 0 : (double) current / target;
                status = fraction >= 1 ? "done" : elapsed != null && fraction + 0.15 < elapsed ? "behind" : "ok";
                detail = categoryName == null ? "All saving and investing this month" : categoryName + " this month";
            }
            case BALANCE -> {
                List<Account> watched = g.getAccount() != null ? List.of(g.getAccount())
                        : accounts.findByHiddenFalse().stream().filter(a -> a.getType().savingsLike()).toList();
                current = watched.stream().mapToLong(Account::getBalanceCents).sum();
                long target = g.getTargetCents() == null ? 0 : g.getTargetCents();
                fraction = target <= 0 ? 0 : (double) current / target;
                if (g.getTargetDate() != null && current < target) {
                    long months = Math.max(1, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(g.getTargetDate())));
                    needed = (target - current) / months;
                }
                status = fraction >= 1 ? "done" : "ok";
                detail = accountName != null ? accountName : "All savings and investment accounts";
            }
            case SAVINGS_RATE -> {
                Double rate = b.savingsRate();
                currentPercent = rate == null ? null : rate * 100;
                current = b.saved();
                double target = g.getTargetPercent() == null ? 0 : g.getTargetPercent();
                fraction = target <= 0 || currentPercent == null ? 0 : currentPercent / target;
                status = fraction >= 1 ? "done" : "ok";
                detail = "Saved and invested, as a share of gross income";
            }
            default -> throw new IllegalStateException();
        }
        return new GoalProgress(g.getId(), g.getName(), g.getType().name(), g.getTargetCents(), g.getTargetPercent(),
                accountId, accountName, categoryId, categoryName, g.getTargetDate(), current, currentPercent,
                fraction, needed, status, detail);
    }
}
