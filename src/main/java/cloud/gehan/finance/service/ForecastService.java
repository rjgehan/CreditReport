package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Cadence;
import cloud.gehan.finance.model.RecurringKind;
import cloud.gehan.finance.repo.AccountRepository;

/**
 * This month, looking ahead: the pay still coming, the bills still due, what you keep,
 * and so what's free to spend from now to the end of the month. Then whether your usual
 * pace of everyday spending fits in that. Meant to answer "is it OK to spend some?".
 *
 * <p>One-off purchases (marked so in Activity) are left out: they come out of savings,
 * not out of the month.
 */
@Service
@Transactional(readOnly = true)
public class ForecastService {

    /** Complete months the usual pace comes from. */
    static final int MONTHS = 3;

    private final FlowService flow;
    private final Budget budget;
    private final PaycheckService paychecks;
    private final RecurringService recurring;
    private final Settings settings;
    private final AccountRepository accounts;
    private final ZoneId zone;

    public ForecastService(FlowService flow, Budget budget, PaycheckService paychecks, RecurringService recurring,
            Settings settings, AccountRepository accounts, AppProperties props) {
        this.flow = flow;
        this.budget = budget;
        this.paychecks = paychecks;
        this.recurring = recurring;
        this.settings = settings;
        this.accounts = accounts;
        this.zone = props.zoneId();
    }

    /** Money still to come in or go out this month. {@code day} is null when it's an estimate with no date. */
    public record Coming(String name, LocalDate day, long cents, String kind) {
    }

    /** A category's spending so far this month. */
    public record Slice(Long categoryId, String name, long cents) {
    }

    /**
     * @param incomeCents pay received so far plus pay still due
     * @param spentCents spending so far, one-offs not included
     * @param spentByCategory the biggest categories so far, the rest folded into "Everything else"
     * @param oneOffCents one-off purchases this month, outside all of this
     * @param freeCents income, minus spending so far, bills still due and the savings target
     * @param usualRestCents what you usually spend on everyday things from today to the month's end
     * @param endCents free minus the usual rest: what's likely left over on the last day
     * @param history whether the usual pace comes from your own months (false: no complete month yet)
     * @param status ok, tight (the usual pace needs more than is free) or short (bills and spending already exceed pay)
     */
    public record Forecast(String month, int dayOfMonth, int daysInMonth, int daysLeft, long receivedCents,
            List<Coming> incomeComing, long incomeCents, long spentCents, List<Slice> spentByCategory, long oneOffCents,
            List<Coming> billsComing, long billsComingCents, long savingsTargetCents, long freeCents, long perDayCents,
            long perWeekCents, long usualRestCents, long endCents, boolean history, String status,
            long savingsBalanceCents) {
    }

    public Forecast forecast() {
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        LocalDate end = month.atEndOfMonth();
        FlowService.Breakdown b = flow.month(month);

        long received = b.cashIncome();
        List<Coming> incomeComing = payStillDue(today, end);
        if (incomeComing.isEmpty()) {
            // No paycheck rhythm to go by: the rest of a usual month's pay, undated.
            Long usual = budget.expectedIncome(month);
            if (usual != null && usual > received) {
                incomeComing.add(new Coming("Rest of a usual month's pay", null, usual - received, "pay"));
            }
        }
        long income = received + incomeComing.stream().mapToLong(Coming::cents).sum();

        List<Coming> bills = billsStillDue(today, end);
        long billsCents = bills.stream().mapToLong(Coming::cents).sum();
        Long target = settings.savingsTargetCents();
        long saving = target == null ? 0 : target;
        long spent = b.spent();
        long free = income - spent - billsCents - saving;
        int daysLeft = (int) ChronoUnit.DAYS.between(today, end) + 1;
        long perDay = Math.max(0, free) / daysLeft;

        // The usual pace: what your last few complete months spent on everyday things
        // (not bills, not one-offs) after this day of the month.
        int day = today.getDayOfMonth();
        long restSum = 0;
        int months = 0;
        for (int i = 1; i <= 12 && months < MONTHS; i++) {
            YearMonth p = month.minusMonths(i);
            if (!budget.covered(p)) {
                continue;
            }
            long whole = flow.month(p).variableSpent();
            long through = budget.variableSpentThrough(p, day);
            restSum += Math.max(0, whole - through);
            months++;
        }
        // Subscriptions and other regular charges are everyday spending too, and already
        // counted as still due, so they come off the usual rest.
        long regularDue = bills.stream().filter(c -> !RecurringKind.BILL.name().equals(c.kind()))
                .mapToLong(Coming::cents).sum();
        long usualRest = months == 0 ? 0 : Math.max(0, restSum / months - regularDue);
        long endCents = free - usualRest;
        String status = free < 0 ? "short" : endCents < 0 ? "tight" : "ok";

        long savingsBalance = accounts.findByHiddenFalse().stream().filter(a -> a.getType() == AccountType.SAVINGS)
                .mapToLong(Account::getBalanceCents).sum();
        return new Forecast(month.toString(), day, month.lengthOfMonth(), daysLeft, received, incomeComing, income,
                spent, slices(b), b.oneOffCents(), bills, billsCents, saving, free, perDay, perDay * 7, usualRest,
                endCents, months > 0, status, savingsBalance);
    }

    /** Each confirmed paycheck's paydays from today to the month's end (a late one counts as today). */
    private List<Coming> payStillDue(LocalDate today, LocalDate end) {
        List<Coming> out = new ArrayList<>();
        for (PaycheckService.Detected d : paychecks.overview().detected()) {
            if (!d.tracked() || d.accountHidden() || d.nextExpected() == null) {
                continue;
            }
            for (LocalDate day = d.nextExpected(); !day.isAfter(end); day = nextPayday(day, d.frequency())) {
                if (!day.isBefore(today.withDayOfMonth(1))) {
                    out.add(new Coming(title(d.pattern()), day.isBefore(today) ? today : day, d.averageCents(), "pay"));
                }
            }
        }
        out.sort(Comparator.comparing(Coming::day));
        return out;
    }

    static LocalDate nextPayday(LocalDate day, String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> day.plusWeeks(1);
            case "BIWEEKLY" -> day.plusWeeks(2);
            case "SEMIMONTHLY" -> day.getDayOfMonth() < 15 ? day.withDayOfMonth(15)
                    : day.getDayOfMonth() < day.lengthOfMonth() ? day.withDayOfMonth(day.lengthOfMonth())
                    : day.plusMonths(1).withDayOfMonth(15);
            default -> day.plusMonths(1);
        };
    }

    /** Every regular charge (bills, subscriptions, the rest) due from today to the month's end. */
    private List<Coming> billsStillDue(LocalDate today, LocalDate end) {
        List<Coming> out = new ArrayList<>();
        for (RecurringService.Item item : recurring.overview().items()) {
            if (item.nextDate() == null) {
                continue;
            }
            Cadence cadence = Cadence.valueOf(item.cadence());
            LocalDate first = item.nextDate();
            LocalDate day = first;
            for (int k = 1; !day.isAfter(end); k++) {
                if (!day.isBefore(today)) {
                    out.add(new Coming(item.name(), day, item.amountCents(), item.kind().name()));
                }
                day = cadence.after(first, k);
            }
        }
        out.sort(Comparator.comparing(Coming::day));
        return out;
    }

    /** The four biggest spending categories so far, then everything else together. */
    private static List<Slice> slices(FlowService.Breakdown b) {
        List<Slice> out = new ArrayList<>();
        long other = 0;
        int shown = 0;
        for (Map.Entry<FlowService.Bucket, Long> e : b.expenses().entrySet()) {
            if (shown < 4) {
                out.add(new Slice(e.getKey().categoryId(), e.getKey().name(), e.getValue()));
                shown++;
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            out.add(new Slice(null, "Everything else", other));
        }
        return out;
    }

    private static String title(String words) {
        StringBuilder out = new StringBuilder();
        for (String w : words.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }
}
