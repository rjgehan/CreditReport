package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.PayFrequency;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Finds paychecks: deposits from the same payer, of similar size, arriving on a
 * weekly, every-other-week, twice-a-month or monthly rhythm.
 */
@Service
@Transactional(readOnly = true)
public class PaycheckService {

    static final int LOOKBACK_DAYS = 180;
    static final long MIN_CENTS = 100_00;

    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final RuleService ruleService;
    private final ZoneId zone;

    public PaycheckService(TransactionRepository txns, CategoryRepository categories, CategoryRuleRepository rules,
            RuleService ruleService, AppProperties props) {
        this.txns = txns;
        this.categories = categories;
        this.rules = rules;
        this.ruleService = ruleService;
        this.zone = props.zoneId();
    }

    /**
     * @param pattern words to match future deposits on; what "Track as paycheck" creates a rule from
     * @param tracked every deposit in the group is already categorised as Paycheck
     * @param accountHidden it lands in an account you hid, so it won't count as income until you show it
     */
    public record Detected(String pattern, String example, String accountName, boolean accountHidden, String frequency,
            int count, long averageCents, long monthlyEstimateCents, LocalDate lastDate, LocalDate nextExpected,
            boolean tracked) {
    }

    public record MonthPay(String month, long totalCents, int count) {
    }

    public record Overview(List<Detected> detected, List<MonthPay> byMonth, long averageMonthlyCents) {
    }

    public Overview overview() {
        LocalDate today = LocalDate.now(zone);
        List<Transaction> recent = txns.findByDayGreaterThanEqual(today.minusDays(LOOKBACK_DAYS));
        List<Detected> detected = detect(recent, today);

        Map<YearMonth, long[]> months = new LinkedHashMap<>();
        YearMonth current = YearMonth.from(today);
        for (int i = 5; i >= 0; i--) {
            months.put(current.minusMonths(i), new long[2]);
        }
        for (Transaction t : recent) {
            if (t.getCategory() != null && ReferenceData.PAYCHECK.equals(t.getCategory().getName())
                    && t.getAmountCents() > 0 && !t.getAccount().isHidden()) {
                long[] m = months.get(YearMonth.from(t.getDay()));
                if (m != null) {
                    m[0] += t.getAmountCents();
                    m[1]++;
                }
            }
        }
        List<MonthPay> byMonth = new ArrayList<>();
        months.forEach((m, v) -> byMonth.add(new MonthPay(m.toString(), v[0], (int) v[1])));
        // Average over complete months only; the current one is still filling in.
        long avg = (long) byMonth.subList(0, byMonth.size() - 1).stream()
                .filter(m -> m.count() > 0).mapToLong(MonthPay::totalCents).average().orElse(0);
        return new Overview(detected, byMonth, avg);
    }

    List<Detected> detect(List<Transaction> recent, LocalDate today) {
        return groups(recent, today).stream().map(Group::detected).toList();
    }

    /** A detected paycheck and the deposits behind it. */
    private record Group(Detected detected, List<Transaction> deposits) {
    }

    private List<Group> groups(List<Transaction> recent, LocalDate today) {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : recent) {
            boolean notTransfer = t.getCategory() == null
                    || t.getCategory().getKind() == CategoryKind.INCOME
                    || t.getCategorySource() == CategorySource.DEFAULT;
            if (depositAccount(t) && notTransfer && t.getAmountCents() >= MIN_CENTS && t.getPairedTxnId() == null) {
                groups.computeIfAbsent(payerKey(t.getDescription()), k -> new ArrayList<>()).add(t);
            }
        }

        List<Group> found = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : groups.entrySet()) {
            List<Transaction> list = e.getValue();
            if (list.size() < 2 || e.getKey().isBlank()) {
                continue;
            }
            list.sort(Comparator.comparing(Transaction::getDay));
            long[] amounts = list.stream().mapToLong(Transaction::getAmountCents).sorted().toArray();
            long median = amounts[amounts.length / 2];
            List<Transaction> similar = list.stream()
                    .filter(t -> Math.abs(t.getAmountCents() - median) <= median * 0.35).toList();
            if (similar.size() < 2) {
                continue;
            }
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < similar.size(); i++) {
                gaps.add(ChronoUnit.DAYS.between(similar.get(i - 1).getDay(), similar.get(i).getDay()));
            }
            long gap = gaps.stream().sorted().toList().get(gaps.size() / 2);
            PayFrequency frequency = frequency(gap, gaps, similar);
            if (frequency == null) {
                continue;
            }
            long average = (long) similar.stream().mapToLong(Transaction::getAmountCents).average().orElse(0);
            Transaction last = similar.get(similar.size() - 1);
            // A payer that stopped paying more than two cycles ago isn't a paycheck any more.
            if (ChronoUnit.DAYS.between(last.getDay(), today) > gap * 2 + 5) {
                continue;
            }
            boolean tracked = similar.stream().allMatch(t -> t.getCategory() != null
                    && ReferenceData.PAYCHECK.equals(t.getCategory().getName()));
            found.add(new Group(new Detected(e.getKey(), last.getDescription(), last.getAccount().getName(),
                    last.getAccount().isHidden(), frequency.name(), similar.size(), average,
                    average * frequency.perYear() / 12, last.getDay(), next(last.getDay(), gap, frequency, similar), tracked),
                    similar));
        }
        found.sort(Comparator.comparingLong((Group g) -> g.detected().monthlyEstimateCents()).reversed());
        return found;
    }

    /**
     * What a month of pay usually comes to, from the paychecks you've confirmed in
     * accounts you haven't hidden. For a new connection without a full month behind it.
     */
    public long confirmedMonthlyPay() {
        return overview().detected().stream().filter(d -> d.tracked() && !d.accountHidden())
                .mapToLong(Detected::monthlyEstimateCents).sum();
    }

    private static boolean depositAccount(Transaction t) {
        AccountType type = t.getAccount().getType();
        return type == AccountType.CHECKING || type == AccountType.SAVINGS;
    }

    private static PayFrequency frequency(long gap, List<Long> gaps, List<Transaction> deposits) {
        if (gap >= 6 && gap <= 8) {
            return PayFrequency.WEEKLY;
        }
        if (gap >= 12 && gap <= 17) {
            // Every other week is always 14 days and drifts through the month; twice a
            // month lands on the same two days, 13 to 16 days apart.
            boolean fortnights = gaps.stream().allMatch(g -> g == 14);
            long distinctDays = deposits.stream().map(t -> t.getDay().getDayOfMonth() / 4).distinct().count();
            return !fortnights && distinctDays <= 3 ? PayFrequency.SEMIMONTHLY : PayFrequency.BIWEEKLY;
        }
        if (gap >= 27 && gap <= 33) {
            return PayFrequency.MONTHLY;
        }
        return null;
    }

    private static LocalDate next(LocalDate last, long gap, PayFrequency frequency, List<Transaction> deposits) {
        if (frequency == PayFrequency.SEMIMONTHLY) {
            return last.getDayOfMonth() <= 15 ? last.withDayOfMonth(last.lengthOfMonth()) : last.plusMonths(1).withDayOfMonth(15);
        }
        if (frequency == PayFrequency.MONTHLY) {
            return last.plusMonths(1);
        }
        return last.plusDays(frequency == PayFrequency.WEEKLY ? 7 : 14);
    }

    /**
     * @param matched deposits found for it
     * @param refiled transactions that changed category as a result
     */
    public record Filed(int matched, int refiled) {
        static final Filed NONE = new Filed(0, 0);

        Filed plus(Filed other) {
            return new Filed(matched + other.matched, refiled + other.refiled);
        }
    }

    /** Filing anything as Paycheck needs the Paycheck category to still exist. */
    public boolean canFile() {
        return categories.findByNameIgnoreCase(ReferenceData.PAYCHECK).isPresent();
    }

    /**
     * "That's my pay", for a payer {@link Detected} found: its deposits are Paycheck
     * from now on, through a rule, and the ones already here are filed as Paycheck
     * straight away. The first time, that includes any you'd filed as something else
     * by hand; after that, a deposit you've re-filed yourself stays as you left it.
     */
    @Transactional
    public Filed track(String payer) {
        Group group = group(payer.trim().toUpperCase(Locale.ROOT));
        if (group == null) {
            return Filed.NONE;
        }
        Category paycheck = categories.findByNameIgnoreCase(ReferenceData.PAYCHECK)
                .orElseThrow(() -> new IllegalStateException("The Paycheck category was deleted; create it again first."));

        // One rule per payer however many times it's tapped. A rule of yours with the same
        // words but another purpose is left as it is, and so is the order you've ranked yours in.
        String words = ruleWords(group.deposits().stream().map(Transaction::getDescription).toList(),
                group.detected().pattern());
        boolean exists = rules.findAll().stream().anyMatch(r -> r.getPriority() >= ReferenceData.USER_PRIORITY
                && r.getPattern().equalsIgnoreCase(words) && r.getSign() == AmountSign.IN && r.getAccountType() == null
                && paycheck.getId().equals(r.getCategory().getId()));
        int changed = 0;
        if (!exists) {
            rules.saveAndFlush(new CategoryRule(words, paycheck, AmountSign.IN, null,
                    Math.max(ReferenceData.USER_PRIORITY, rules.maxPriority() + 1)));
            changed = ruleService.reapply();
        }

        // The deposits you were shown, whatever any rule or earlier choice says.
        for (Transaction t : group.deposits()) {
            boolean yours = t.getCategorySource() == CategorySource.MANUAL;
            if ((t.getCategory() == null || !paycheck.getId().equals(t.getCategory().getId())) && !(exists && yours)) {
                t.setCategory(paycheck);
                t.setCategorySource(CategorySource.MANUAL);
                changed++;
            }
        }
        return new Filed(group.deposits().size(), changed);
    }

    /**
     * A paystub names its deposits by a few words from their description. Every
     * regular deposit those words find, and that isn't already Paycheck, is tracked
     * under its own payer, so the paystub's words themselves (maybe just "TARGET")
     * never become a rule.
     */
    @Transactional
    public Filed trackPaystub(String pattern) {
        WordMatch words = WordMatch.of(pattern);
        LocalDate today = LocalDate.now(zone);
        Filed filed = Filed.NONE;
        for (Group g : groups(txns.findByDayGreaterThanEqual(today.minusDays(LOOKBACK_DAYS)), today)) {
            if (g.deposits().stream().anyMatch(t -> words.in(t.matchText()))) {
                filed = filed.plus(g.detected().tracked()
                        ? new Filed(g.deposits().size(), 0) : track(g.detected().pattern()));
            }
        }
        return filed;
    }

    private Group group(String payer) {
        LocalDate today = LocalDate.now(zone);
        return groups(txns.findByDayGreaterThanEqual(today.minusDays(LOOKBACK_DAYS)), today).stream()
                .filter(g -> g.detected().pattern().equals(payer)).findFirst().orElse(null);
    }

    /**
     * The words a payer's rule looks for: the opening words all its deposits share,
     * through the payer and to at least three, so "TARGET DES PAYROLL" rather than
     * "TARGET" (which a store refund matches too), and "ORIG CO NAME COMPANY" rather
     * than "COMPANY". Every one of the deposits contains them.
     */
    static String ruleWords(List<String> descriptions, String payer) {
        List<List<String>> all = descriptions.stream().map(d -> List.of(WordMatch.loosen(d).split(" "))).toList();
        List<String> common = new ArrayList<>();
        for (String word : all.get(0)) {
            int at = common.size();
            if (word.isEmpty() || !all.stream().allMatch(w -> w.size() > at && w.get(at).equals(word))) {
                break;
            }
            common.add(word);
        }
        List<String> payerWords = List.of(payer.split(" "));
        int start = Collections.indexOfSubList(common, payerWords);
        if (start < 0) {
            return payer;
        }
        int end = Math.min(common.size(), Math.max(3, start + payerWords.size()));
        return String.join(" ", common.subList(0, end));
    }

    /** ACH detail as some banks show it: the payer sits between "CO NAME:" and the next field. */
    private static final Pattern ORIGINATOR =
            Pattern.compile("\\bCO NAME:\\s*(.+?)\\s*(?:\\bCO ENTRY\\b|\\bCO ID\\b|\\bENTRY DESCR\\b|\\bSEC:|$)");

    /**
     * The payer part of a deposit description, stable from one paycheck to the
     * next: "ACME CORP PAYROLL PPD ID: 1234567 03/14" becomes "ACME CORP PAYROLL",
     * and "ORIG CO NAME:ACME, INC. CO ENTRY DESCR:PAYROLL" becomes "ACME INC".
     * The result is words {@link WordMatch} finds in the description it came from.
     */
    static String payerKey(String description) {
        String text = description.toUpperCase(Locale.ROOT);
        Matcher named = ORIGINATOR.matcher(text);
        if (named.find()) {
            text = named.group(1);
        }
        text = WordMatch.loosen(text.replaceAll("\\b(PPD|CCD|WEB|ID|CO|DES|INDN|ENTRY|DESCR|TRACE|ORIG|DATE|SEC)\\b.*$", " "));
        String[] words = text.split(" ");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 3))).trim();
    }
}
