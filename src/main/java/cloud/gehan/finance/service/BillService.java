package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Bills: the spending categories marked as the same every month (rent, student
 * loans). How much has gone to each, month by month, and the payments from before
 * the bank history started, typed in by hand.
 */
@Service
@Transactional(readOnly = true)
public class BillService {

    static final int MAX_MONTHS = 60;

    private final CategoryRepository categories;
    private final TransactionRepository txns;
    private final AccountRepository accounts;
    private final ZoneId zone;

    public BillService(CategoryRepository categories, TransactionRepository txns, AccountRepository accounts,
            AppProperties props) {
        this.categories = categories;
        this.txns = txns;
        this.accounts = accounts;
        this.zone = props.zoneId();
    }

    public record BillMonth(String month, long paidCents, int count) {
    }

    /**
     * @param since the earliest payment on record, or null with none
     * @param usualAccountId where its payments usually come from, for adding old ones
     * @param months newest first
     */
    public record Bill(Long categoryId, String name, String icon, long thisYearCents, long allTimeCents,
            LocalDate since, LocalDate lastPaid, long lastPaidCents, Long usualAccountId, List<BillMonth> months) {
    }

    /**
     * @param historyStart where the bank history begins: months from then on are already
     *        there, so only earlier ones can be filled in by hand. Null with no history yet.
     */
    public record Bills(LocalDate historyStart, List<Bill> bills) {
    }

    public Bills bills(int monthCount) {
        int count = Math.max(1, Math.min(monthCount, MAX_MONTHS));
        LocalDate today = LocalDate.now(zone);
        List<Bill> bills = new ArrayList<>();
        for (Category c : categories.findAllByOrderBySortOrderAscNameAsc()) {
            if (c.getKind() == CategoryKind.EXPENSE && c.isFixedCost()) {
                bills.add(bill(c, today, count));
            }
        }
        return new Bills(txns.earliestDay().orElse(null), bills);
    }

    private Bill bill(Category c, LocalDate today, int count) {
        List<Transaction> paid = txns.findVisibleByCategory(c);
        Map<YearMonth, long[]> byMonth = new HashMap<>();
        Map<Long, Integer> byAccount = new HashMap<>();
        long year = 0;
        long all = 0;
        LocalDate since = null;
        Transaction last = null;
        for (Transaction t : paid) {
            long cents = -t.getAmountCents();
            all += cents;
            if (t.getDay().getYear() == today.getYear()) {
                year += cents;
            }
            long[] m = byMonth.computeIfAbsent(YearMonth.from(t.getDay()), k -> new long[2]);
            m[0] += cents;
            m[1]++;
            byAccount.merge(t.getAccount().getId(), 1, Integer::sum);
            since = since == null || t.getDay().isBefore(since) ? t.getDay() : since;
            if (t.getAmountCents() < 0 && last == null) {
                last = t;
            }
        }
        List<BillMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (int i = 0; i < count; i++) {
            YearMonth ym = current.minusMonths(i);
            long[] m = byMonth.getOrDefault(ym, new long[2]);
            months.add(new BillMonth(ym.toString(), m[0], (int) m[1]));
        }
        Long usual = byAccount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(null);
        return new Bill(c.getId(), c.getName(), c.getIcon(), year, all, since, last == null ? null : last.getDay(),
                last == null ? 0 : -last.getAmountCents(), usual, months);
    }

    /**
     * Payments from before the bank history, one per day given, filed under the bill
     * and checked off (you typed them in, so they're right).
     *
     * @return how many were added
     */
    @Transactional
    public int addPayments(Long categoryId, Long accountId, long amountCents, List<LocalDate> days, String description) {
        Category c = categories.findById(categoryId).orElseThrow();
        if (c.getKind() != CategoryKind.EXPENSE) {
            throw new IllegalArgumentException(c.getName() + " isn't a spending category.");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("The amount must be more than zero.");
        }
        if (days == null || days.isEmpty() || days.size() > MAX_MONTHS) {
            throw new IllegalArgumentException("Pick between 1 and " + MAX_MONTHS + " dates.");
        }
        Account account = accounts.findById(accountId).orElseThrow();
        // From the day the bank's history of this account starts, its payments are already here,
        // filed somewhere; adding them again would count them twice.
        LocalDate bankStart = account.isManual() ? null : txns.earliestBankDay(account).orElse(null);
        if (bankStart != null && days.stream().anyMatch(d -> !d.isBefore(bankStart))) {
            throw new IllegalArgumentException("The bank history for " + account.getName() + " starts " + bankStart
                    + ". Payments from then on are already in Activity; file them there instead.");
        }
        String text = description == null || description.isBlank() ? c.getName() + " payment" : description.trim();
        long stamp = System.nanoTime();
        List<LocalDate> sorted = days.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        for (LocalDate day : sorted) {
            Transaction t = new Transaction(account, Transaction.BILL_BACKFILL + stamp++, day, -amountCents, text);
            t.setCategory(c);
            t.setCategorySource(CategorySource.MANUAL);
            t.setReviewed(true);
            txns.save(t);
        }
        return sorted.size();
    }
}
