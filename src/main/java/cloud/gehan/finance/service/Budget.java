package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * The month as one sum: income, minus what you want to keep, minus what you've
 * spent, is what's left to spend.
 *
 * <p>Mid-month, income counts the paychecks you can still expect, not only the
 * ones that have landed; otherwise every month would look broke on the 2nd. And
 * pace is measured against how your own months usually go, so rent on the 1st
 * doesn't read as overspending for the first half of every month.
 */
@Service
@Transactional(readOnly = true)
public class Budget {

    /** Complete months looked back over, for expected income and the usual spending curve. */
    static final int LOOKBACK = 3;

    private final FlowService flow;
    private final Settings settings;
    private final TransactionRepository txns;
    private final ZoneId zone;

    public Budget(FlowService flow, Settings settings, TransactionRepository txns, AppProperties props) {
        this.flow = flow;
        this.settings = settings;
        this.txns = txns;
        this.zone = props.zoneId();
    }

    /**
     * @param incomeEstimated income is the expected figure, more than has arrived so far
     * @param perDayLeftCents left to spend divided by the days remaining, today included; current month only
     * @param spentFraction spent over spendable (1 when nothing was spendable but something was spent)
     * @param expectedFraction how much of a month's spending has usually happened by today (1 for past months)
     * @param status ok, watch or over: how spending is pacing against what's spendable
     * @param verdict the same in words
     */
    public record MonthPlan(String month, boolean current, int dayOfMonth, int daysInMonth,
            long receivedIncomeCents, long incomeCents, boolean incomeEstimated, Long savingsTargetCents,
            long spentCents, long spendableCents, long leftToSpendCents, Long perDayLeftCents,
            double spentFraction, double expectedFraction, String status, String verdict) {
    }

    public MonthPlan plan(YearMonth month) {
        return plan(month, flow.month(month));
    }

    public MonthPlan plan(YearMonth month, FlowService.Breakdown b) {
        LocalDate today = LocalDate.now(zone);
        boolean current = YearMonth.from(today).equals(month);
        int days = month.lengthOfMonth();
        int day = current ? today.getDayOfMonth() : days;

        long received = b.cashIncome();
        long income = received;
        boolean estimated = false;
        if (current) {
            Long expected = expectedIncome(month);
            if (expected != null && expected > received) {
                income = expected;
                estimated = true;
            }
        }
        Long target = settings.savingsTargetCents();
        long spent = b.spent();
        long spendable = income - (target == null ? 0 : target);
        long left = spendable - spent;
        Long perDay = current ? left / Math.max(1, days - day + 1) : null;

        double f = spendable > 0 ? (double) spent / spendable : (spent > 0 ? 1 : 0);
        double e = current ? usualSpendingShare(month, day) : 1;
        String status;
        String verdict;
        if (left < 0) {
            status = "over";
            verdict = current ? "Over budget" : "Overspent";
        } else if (current && (f > e + 0.1 || f >= 0.9)) {
            status = "watch";
            verdict = "Above pace";
        } else {
            status = "ok";
            verdict = current ? "On pace" : "On target";
        }
        return new MonthPlan(month.toString(), current, day, days, received, income, estimated, target, spent,
                spendable, left, perDay, f, e, status, verdict);
    }

    /** The average take-home of the last few complete months that had any, or null with no history. */
    public Long expectedIncome(YearMonth month) {
        long sum = 0;
        int count = 0;
        for (int i = 1; i <= LOOKBACK; i++) {
            YearMonth p = month.minusMonths(i);
            if (!covered(p)) {
                continue;
            }
            long income = flow.month(p).cashIncome();
            if (income > 0) {
                sum += income;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /**
     * How much of a month's spending has usually happened by this day of the
     * month, from your last few complete months. Falls back to an even pace with
     * no history.
     */
    public double usualSpendingShare(YearMonth month, int day) {
        if (day >= month.lengthOfMonth()) {
            return 1;
        }
        double sum = 0;
        int count = 0;
        for (int i = 1; i <= LOOKBACK; i++) {
            YearMonth p = month.minusMonths(i);
            if (!covered(p)) {
                continue;
            }
            long total = flow.month(p).spent();
            if (total <= 0) {
                continue;
            }
            long soFar = spentThrough(p, day);
            sum += Math.min(1, (double) soFar / total);
            count++;
        }
        return count == 0 ? (double) day / month.lengthOfMonth() : sum / count;
    }

    /** Spending from the 1st through {@code day} of a month (clamped to its length). */
    public long spentThrough(YearMonth month, int day) {
        return flow.compute(month.atDay(1), month.atDay(Math.min(day, month.lengthOfMonth()))).spent();
    }

    /**
     * Whether a month is fully inside the transaction history. The month data
     * starts in is usually partial, and averaging it in would drag every
     * estimate down.
     */
    public boolean covered(YearMonth month) {
        LocalDate earliest = txns.earliestDay().orElse(null);
        return earliest != null && !earliest.isAfter(month.atDay(1).plusDays(2));
    }
}
