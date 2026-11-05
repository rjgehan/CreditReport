package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Cadence;
import cloud.gehan.finance.model.PayFrequency;
import cloud.gehan.finance.model.RecurringKind;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.TransactionRepository;

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
    /** A payday this many days late isn't counted on: maybe the job or the schedule changed. */
    static final int PAY_GRACE_DAYS = 4;

    private final FlowService flow;
    private final Budget budget;
    private final PaycheckService paychecks;
    private final RecurringService recurring;
    private final Settings settings;
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final ZoneId zone;

    public ForecastService(FlowService flow, Budget budget, PaycheckService paychecks, RecurringService recurring,
            Settings settings, AccountRepository accounts, TransactionRepository txns, AppProperties props) {
        this.flow = flow;
        this.budget = budget;
        this.paychecks = paychecks;
        this.recurring = recurring;
        this.settings = settings;
        this.accounts = accounts;
        this.txns = txns;
        this.zone = props.zoneId();
    }

    /** Money still to come in or go out this month. {@code day} is null when it's an estimate with no date. */
    public record Coming(String name, LocalDate day, long cents, String kind) {
    }

    /**
     * A category's spending so far this month.
     *
     * @param rest the smaller categories folded together, not one category
     */
    public record Slice(Long categoryId, String name, long cents, boolean rest) {
    }

    /**
     * @param incomeCents pay received so far plus pay still due
     * @param spentCents spending so far, one-offs not included
     * @param spentByCategory the biggest categories so far, the rest folded into "Everything else"
     * @param oneOffCents one-off purchases this month, outside all of this
     * @param roomCents income minus spending so far and bills still due: what's left before saving anything
     * @param freeCents room minus the savings target, never below zero
     * @param usualRestCents what you usually spend on everyday things from today to the month's end
     * @param endCents free minus the usual rest: what's likely left over on the last day, after saving
     * @param history whether the usual pace comes from your own months (false: no complete month yet)
     * @param status unknown (no pay known this month), short (bills and spending are more than pay),
     *        under (the full savings target doesn't fit this month), tight (it fits, but your usual
     *        pace from here needs more than is free) or ok
     */
    public record Forecast(String month, int dayOfMonth, int daysInMonth, int daysLeft, long receivedCents,
            List<Coming> incomeComing, long incomeCents, long spentCents, List<Slice> spentByCategory, long oneOffCents,
            List<Coming> billsComing, long billsComingCents, long savingsTargetCents, long roomCents, long freeCents,
            long perDayCents, long perWeekCents, long usualRestCents, long endCents, boolean history, String status,
            long savingsBalanceCents) {
    }

    public Forecast forecast() {
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        LocalDate end = month.atEndOfMonth();
        FlowService.Breakdown b = flow.month(month);
        List<Transaction> soFar = txns.findVisibleBetween(month.atDay(1), today);

        long received = b.cashIncome();
        List<Coming> incomeComing = payStillDue(today, end, soFar);
        if (incomeComing.isEmpty()) {
            // No paycheck rhythm to go by: the rest of a usual month's pay, undated.
            Long usual = budget.expectedIncome(month);
            if (usual != null && usual > received) {
                incomeComing.add(new Coming("Rest of a usual month's pay", null, usual - received, "pay"));
            }
        }
        long income = received + incomeComing.stream().mapToLong(Coming::cents).sum();

        Due due = billsStillDue(today, end, soFar);
        long billsCents = due.list().stream().mapToLong(Coming::cents).sum();
        Long target = settings.savingsTargetCents();
        long saving = target == null ? 0 : target;
        long spent = b.spent();
        long room = income - spent - billsCents;
        long free = Math.max(0, room - saving);
        int daysLeft = (int) ChronoUnit.DAYS.between(today, end) + 1;
        long perDay = free / daysLeft;

        // The usual pace: what your last few complete months spent on everyday things
        // (not bills, not one-offs) from this day of the month on.
        int day = today.getDayOfMonth();
        long restSum = 0;
        int months = 0;
        for (int i = 1; i <= 12 && months < MONTHS; i++) {
            YearMonth p = month.minusMonths(i);
            if (!budget.covered(p)) {
                continue;
            }
            long whole = flow.month(p).variableSpent();
            if (whole <= 0) {
                continue; // a month with no spending on record says nothing about your pace
            }
            long before = day <= 1 ? 0 : budget.variableSpentThrough(p, day - 1);
            restSum += Math.max(0, whole - before);
            months++;
        }
        // Monthly subscriptions due from here were in those months' spending too, and
        // are already counted as still due, so they come off the usual rest.
        long usualRest = months == 0 ? 0 : Math.max(0, restSum / months - due.inUsualPace());
        long endCents = free - usualRest;

        String status;
        if (income == 0) {
            status = "unknown";
        } else if (room < 0) {
            status = "short";
        } else if (room < saving) {
            status = "under";
        } else if (endCents < 0) {
            status = "tight";
        } else {
            status = "ok";
        }

        long savingsBalance = accounts.findByHiddenFalse().stream().filter(a -> a.getType() == AccountType.SAVINGS)
                .mapToLong(Account::getBalanceCents).sum();
        return new Forecast(month.toString(), day, month.lengthOfMonth(), daysLeft, received, incomeComing, income,
                spent, slices(b), b.oneOffCents(), due.list(), billsCents, saving, room, free, perDay, perDay * 7,
                usualRest, endCents, months > 0, status, savingsBalance);
    }

    /**
     * Each confirmed paycheck's paydays from today to the month's end. A payday a few days
     * late counts as today; one that's more than that late isn't counted on. A payday is
     * skipped when a deposit from that payer already came in around then (paid early, or a
     * bonus-sized check that didn't look like the usual one).
     */
    private List<Coming> payStillDue(LocalDate today, LocalDate end, List<Transaction> soFar) {
        List<Coming> out = new ArrayList<>();
        LocalDate first = today.withDayOfMonth(1);
        for (PaycheckService.Detected d : paychecks.overview().detected()) {
            if (!d.tracked() || d.accountHidden() || d.nextExpected() == null) {
                continue;
            }
            PayFrequency frequency = PayFrequency.valueOf(d.frequency());
            for (LocalDate day = d.nextExpected(); !day.isAfter(end);
                    day = PaycheckService.nextPayday(day, frequency, d.firstOfMonth())) {
                if (day.isBefore(first) || ChronoUnit.DAYS.between(day, today) > PAY_GRACE_DAYS) {
                    continue;
                }
                if (arrived(soFar, d.pattern(), day)) {
                    continue;
                }
                out.add(new Coming(title(d.pattern()), day.isBefore(today) ? today : day, d.averageCents(), "pay"));
            }
        }
        out.sort(Comparator.comparing(Coming::day));
        return out;
    }

    private static boolean arrived(List<Transaction> soFar, String payer, LocalDate payday) {
        return soFar.stream().anyMatch(t -> t.getAmountCents() >= PaycheckService.MIN_CENTS
                && Math.abs(ChronoUnit.DAYS.between(t.getDay(), payday)) <= PAY_GRACE_DAYS
                && payer.equals(PaycheckService.payerKey(t.getDescription())));
    }

    /**
     * @param list what's still due, by date
     * @param inUsualPace the part of it that past months' everyday spending already included
     *        (monthly subscriptions and the like, found in the history)
     */
    record Due(List<Coming> list, long inUsualPace) {
    }

    /**
     * Every regular charge (bills, subscriptions, the rest) due from today to the month's end.
     * One a few days late still counts, as due today; one later than that may have stopped
     * (the old apartment's rent) and isn't counted. A charge already pending this month, or
     * one you added by hand that's already been paid, isn't due again.
     */
    private Due billsStillDue(LocalDate today, LocalDate end, List<Transaction> soFar) {
        List<Coming> out = new ArrayList<>();
        long inPace = 0;
        Set<Transaction> used = new HashSet<>();
        for (RecurringService.Item item : recurring.overview().items()) {
            if (item.expected() == null) {
                continue;
            }
            Cadence cadence = Cadence.valueOf(item.cadence());
            int grace = cadence == Cadence.WEEKLY || cadence == Cadence.BIWEEKLY ? 2 : 5;
            boolean byHand = item.chargeId() != null;
            for (int k = 0; ; k++) {
                LocalDate day = cadence.after(item.expected(), k);
                if (day.isAfter(end)) {
                    break;
                }
                if (day.isBefore(today) && ChronoUnit.DAYS.between(day, today) > grace) {
                    continue;
                }
                if (alreadyThrough(item, day, soFar, used, byHand)) {
                    continue;
                }
                out.add(new Coming(item.name(), day.isBefore(today) ? today : day, item.amountCents(), item.kind().name()));
                boolean everyMonth = cadence == Cadence.MONTHLY || cadence == Cadence.WEEKLY || cadence == Cadence.BIWEEKLY;
                if (!byHand && everyMonth && item.kind() != RecurringKind.BILL) {
                    inPace += item.amountCents();
                }
            }
        }
        out.sort(Comparator.comparing(Coming::day));
        return new Due(out, inPace);
    }

    /**
     * Whether this occurrence has already come through this month: as a pending charge
     * from the same payee (the history only learns of it once it posts), or, for one you
     * added by hand, as any charge with its name and about its amount near the due date.
     */
    private static boolean alreadyThrough(RecurringService.Item item, LocalDate day, List<Transaction> soFar,
            Set<Transaction> used, boolean byHand) {
        long tolerance = Math.max(100, item.amountCents() / 10);
        String payee = item.key() == null ? null : item.key().split("\\|")[0];
        String word = WordMatch.loosen(item.name()).split(" ")[0];
        for (Transaction t : soFar) {
            if (t.getAmountCents() >= 0 || used.contains(t)
                    || Math.abs(-t.getAmountCents() - item.amountCents()) > tolerance) {
                continue;
            }
            boolean match = byHand
                    ? !word.isEmpty() && WordMatch.of(word).in(t.matchText())
                            && Math.abs(ChronoUnit.DAYS.between(t.getDay(), day)) <= 7
                    : t.isPending() && payee != null && payee.equals(RecurringService.key(t));
            if (match) {
                used.add(t);
                return true;
            }
        }
        return false;
    }

    /** The four biggest spending categories so far, then everything else together. */
    private static List<Slice> slices(FlowService.Breakdown b) {
        List<Slice> out = new ArrayList<>();
        long other = 0;
        int shown = 0;
        for (Map.Entry<FlowService.Bucket, Long> e : b.expenses().entrySet()) {
            if (shown < 4) {
                out.add(new Slice(e.getKey().categoryId(), e.getKey().name(), e.getValue(), false));
                shown++;
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            out.add(new Slice(null, "Everything else", other, true));
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
