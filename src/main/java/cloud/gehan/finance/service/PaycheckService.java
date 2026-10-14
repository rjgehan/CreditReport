package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.PayFrequency;
import cloud.gehan.finance.model.Transaction;
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
    private final ZoneId zone;

    public PaycheckService(TransactionRepository txns, AppProperties props) {
        this.txns = txns;
        this.zone = props.zoneId();
    }

    /**
     * @param pattern words to match future deposits on; what "Track as paycheck" creates a rule from
     * @param tracked every deposit in the group is already categorised as Paycheck
     */
    public record Detected(String pattern, String example, String accountName, String frequency, int count,
            long averageCents, long monthlyEstimateCents, LocalDate lastDate, LocalDate nextExpected,
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
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : recent) {
            AccountType type = t.getAccount().getType();
            boolean depositAccount = type == AccountType.CHECKING || type == AccountType.SAVINGS;
            boolean notTransfer = t.getCategory() == null
                    || t.getCategory().getKind() == CategoryKind.INCOME
                    || t.getCategorySource() == cloud.gehan.finance.model.CategorySource.DEFAULT;
            if (depositAccount && notTransfer && t.getAmountCents() >= MIN_CENTS && t.getPairedTxnId() == null) {
                groups.computeIfAbsent(payerKey(t.getDescription()), k -> new ArrayList<>()).add(t);
            }
        }

        List<Detected> found = new ArrayList<>();
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
            PayFrequency frequency = frequency(gap, similar);
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
            found.add(new Detected(e.getKey(), last.getDescription(), last.getAccount().getName(), frequency.name(),
                    similar.size(), average, average * frequency.perYear() / 12, last.getDay(),
                    next(last.getDay(), gap, frequency, similar), tracked));
        }
        found.sort(Comparator.comparingLong(Detected::monthlyEstimateCents).reversed());
        return found;
    }

    private static PayFrequency frequency(long gap, List<Transaction> deposits) {
        if (gap >= 6 && gap <= 8) {
            return PayFrequency.WEEKLY;
        }
        if (gap >= 12 && gap <= 17) {
            // Twice a month lands on the same two days of the month; every other
            // week drifts through the month.
            long distinctDays = deposits.stream().map(t -> t.getDay().getDayOfMonth() / 4).distinct().count();
            return distinctDays <= 3 && deposits.size() >= 4 ? PayFrequency.SEMIMONTHLY : PayFrequency.BIWEEKLY;
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
     * The payer part of a deposit description, stable from one paycheck to the
     * next: "ACME CORP PAYROLL PPD ID: 1234567 03/14" becomes "ACME CORP PAYROLL".
     */
    static String payerKey(String description) {
        String text = description.toUpperCase(Locale.ROOT)
                .replaceAll("\\b(PPD|CCD|WEB|ID|CO|DES|INDN|ENTRY|DESCR|TRACE|ORIG|DATE|SEC)\\b.*$", " ")
                .replaceAll("[^A-Z& ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] words = text.split(" ");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 3))).trim();
    }
}
