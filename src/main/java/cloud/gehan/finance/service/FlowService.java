package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.Paystub;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.PaystubRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Where the money went over a period: the numbers behind the Sankey, the
 * month's totals on Home, and the spending that targets are measured against.
 */
@Service
@Transactional(readOnly = true)
public class FlowService {

    private final TransactionRepository txns;
    private final PaystubRepository paystubs;

    public FlowService(TransactionRepository txns, PaystubRepository paystubs) {
        this.txns = txns;
        this.paystubs = paystubs;
    }

    /** A category, or a stand-in for transactions that have none. */
    /**
     * @param earned income kind: counts as income (work, side hustles), not interest or stray deposits
     * @param fixed expense kind: the same every month (rent, loans)
     */
    public record Bucket(Long categoryId, String name, String icon, CategoryKind kind, boolean earned, boolean fixed) {
    }

    /** One employer's paychecks in the period, expanded from net back to gross. */
    public record PayBreakdown(Paystub stub, int count, long netCents) {
        long gross() { return netCents + stub.deductionsCents() * count; }
        long federal() { return stub.getFederalTaxCents() * count; }
        long state() { return stub.getStateTaxCents() * count; }
        long fica() { return stub.getFicaCents() * count; }
        long retirement() { return stub.getRetirementCents() * count; }
        long benefits() { return stub.getBenefitsCents() * count; }
        long other() { return stub.getOtherDeductionsCents() * count; }
        long match() { return stub.getEmployerMatchCents() * count; }
    }

    /**
     * @param income cash income by category, paychecks explained by a paystub excluded
     * @param expenses spending by category, refunds netted in; never negative
     * @param savings net moved into savings-like accounts by category; may be negative
     */
    public record Breakdown(
            LocalDate from, LocalDate to,
            Map<Bucket, Long> income,
            Map<Bucket, Long> expenses,
            Map<Bucket, Long> savings,
            List<PayBreakdown> pay) {

        public long cashIncome() {
            return income.values().stream().mapToLong(Long::longValue).sum()
                    + pay.stream().mapToLong(PayBreakdown::netCents).sum();
        }

        public long grossIncome() {
            return income.values().stream().mapToLong(Long::longValue).sum()
                    + pay.stream().mapToLong(p -> p.gross() + p.match()).sum();
        }

        /** Spending outside the fixed costs (rent, loans): the part that actually varies. */
        public long variableSpent() {
            return expenses.entrySet().stream().filter(e -> !e.getKey().fixed()).mapToLong(Map.Entry::getValue).sum();
        }

        public long spent() {
            return expenses.values().stream().mapToLong(Long::longValue).sum();
        }

        /** Moved from cash accounts into savings, by the categories that grew. */
        public long savedFromCash() {
            return savings.values().stream().mapToLong(v -> Math.max(0, v)).sum();
        }

        /** Taken back out of savings, by the categories that shrank. */
        public long withdrawn() {
            return savings.values().stream().mapToLong(v -> Math.max(0, -v)).sum();
        }

        public long savedPreTax() {
            return pay.stream().mapToLong(p -> p.retirement() + p.match()).sum();
        }

        /** Net of withdrawals, which is what a savings rate should measure. */
        public long saved() {
            return savedFromCash() - withdrawn() + savedPreTax();
        }

        /** Negative when the month was paid for out of savings or on credit. */
        public long leftover() {
            return cashIncome() + withdrawn() - spent() - savedFromCash();
        }

        public Double savingsRate() {
            long gross = grossIncome();
            return gross <= 0 ? null : (double) saved() / gross;
        }

        public long spentIn(Long categoryId) {
            return expenses.entrySet().stream()
                    .filter(e -> categoryId.equals(e.getKey().categoryId()))
                    .mapToLong(Map.Entry::getValue).sum();
        }

        public long savedIn(Long categoryId) {
            return savings.entrySet().stream()
                    .filter(e -> categoryId.equals(e.getKey().categoryId()))
                    .mapToLong(Map.Entry::getValue).sum();
        }
    }

    public Breakdown month(YearMonth month) {
        return compute(month.atDay(1), month.atEndOfMonth());
    }

    public Breakdown compute(LocalDate from, LocalDate to) {
        Map<Bucket, Long> income = new LinkedHashMap<>();
        Map<Bucket, Long> expenses = new LinkedHashMap<>();
        Map<Bucket, Long> savings = new LinkedHashMap<>();

        List<Paystub> stubs = paystubs.findByActiveTrue();
        Map<Paystub, long[]> payTally = new LinkedHashMap<>();
        List<Pattern> stubPatterns = new ArrayList<>();
        for (Paystub s : stubs) {
            payTally.put(s, new long[2]);
            stubPatterns.add(s.getMatchPattern() == null || s.getMatchPattern().isBlank()
                    ? null : Categorizer.wordPattern(s.getMatchPattern()));
        }

        for (Transaction t : txns.findVisibleBetween(from, to)) {
            Bucket bucket = bucket(t);
            long amount = t.getAmountCents();
            switch (bucket.kind()) {
                case TRANSFER -> {
                }
                case SAVINGS -> {
                    // The far side of a deposit, inside the savings account itself.
                    if (!t.getAccount().getType().savingsLike()) {
                        savings.merge(bucket, -amount, Long::sum);
                    }
                }
                case EXPENSE -> expenses.merge(bucket, -amount, Long::sum);
                case INCOME -> {
                    // Interest, dividends and stray deposits are money in, but not income:
                    // income means what work pays, so the numbers describe a working month.
                    if (!bucket.earned()) {
                        continue;
                    }
                    Paystub stub = ReferenceData.PAYCHECK.equals(bucket.name()) && amount > 0
                            ? stubFor(t, stubs, stubPatterns) : null;
                    if (stub != null) {
                        long[] tally = payTally.get(stub);
                        tally[0]++;
                        tally[1] += amount;
                    } else {
                        income.merge(bucket, amount, Long::sum);
                    }
                }
            }
        }
        income.values().removeIf(v -> v <= 0);
        expenses.values().removeIf(v -> v <= 0);
        savings.values().removeIf(v -> v == 0);

        List<PayBreakdown> pay = new ArrayList<>();
        payTally.forEach((stub, tally) -> {
            if (tally[0] > 0) {
                pay.add(new PayBreakdown(stub, (int) tally[0], tally[1]));
            }
        });
        return new Breakdown(from, to, sortDesc(income), sortDesc(expenses), sortDesc(savings), pay);
    }

    private static Paystub stubFor(Transaction t, List<Paystub> stubs, List<Pattern> patterns) {
        String text = t.matchText().toUpperCase(Locale.ROOT);
        for (int i = 0; i < stubs.size(); i++) {
            if (patterns.get(i) != null && patterns.get(i).matcher(text).find()) {
                return stubs.get(i);
            }
        }
        // One employer and no pattern: every paycheck is theirs.
        if (stubs.size() == 1 && patterns.get(0) == null) {
            return stubs.get(0);
        }
        return null;
    }

    static Bucket bucket(Transaction t) {
        Category c = t.getCategory();
        if (c != null) {
            return new Bucket(c.getId(), c.getName(), c.getIcon(), c.getKind(), c.isEarnedIncome(), c.isFixedCost());
        }
        return t.getAmountCents() > 0
                ? new Bucket(null, ReferenceData.OTHER_INCOME, "💵", CategoryKind.INCOME, false, false)
                : new Bucket(null, ReferenceData.UNCATEGORIZED, "❔", CategoryKind.EXPENSE, false, false);
    }

    private static Map<Bucket, Long> sortDesc(Map<Bucket, Long> map) {
        Map<Bucket, Long> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<Bucket, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    // --- Sankey --------------------------------------------------------------------

    public record Node(String id, String label, String kind, long valueCents, Long categoryId, String icon) {
    }

    public record Link(String source, String target, long valueCents) {
    }

    public record Sankey(
            String from, String to, int months, boolean averaged,
            List<Node> nodes, List<Link> links,
            long grossIncomeCents, long cashIncomeCents, long spentCents, long savedCents,
            long leftoverCents, Double savingsRate, boolean hasPaystub) {
    }

    /** Spending categories shown individually before the rest fold into "Other spending". */
    static final int MAX_EXPENSE_NODES = 10;

    public Sankey sankey(YearMonth first, YearMonth last, boolean average) {
        Breakdown b = compute(first.atDay(1), last.atEndOfMonth());
        int months = (int) ChronoUnit.MONTHS.between(first, last) + 1;
        long div = average ? months : 1;
        SankeyBuilder s = new SankeyBuilder(div);

        // Gross pay, and what came off it before the bank saw anything.
        long preTaxSaved = 0;
        for (PayBreakdown p : b.pay()) {
            String gross = "gross:" + p.stub().getId();
            s.node(gross, p.stub().getEmployer() + " gross pay", "income", null, "💼");
            s.link(gross, "tax:federal", p.federal(), "Federal tax", "tax", "🏛️");
            s.link(gross, "tax:state", p.state(), "State tax", "tax", "🏛️");
            s.link(gross, "tax:fica", p.fica(), "Social Security & Medicare", "tax", "🏛️");
            s.link(gross, "ded:benefits", p.benefits(), "Benefits", "tax", "🩺");
            s.link(gross, "ded:other", p.other(), "Other deductions", "tax", "🧾");
            s.link(gross, "saved", p.retirement(), "Saved & invested", "savings", "🐖");
            s.link(gross, "net:" + p.stub().getId(), p.netCents(), p.stub().getEmployer() + " take-home", "income", "💵");
            s.link("net:" + p.stub().getId(), "income", p.netCents(), "Income", "income", "💰");
            if (p.match() > 0) {
                s.node("match:" + p.stub().getId(), p.stub().getEmployer() + " 401(k) match", "income", null, "🎁");
                s.link("match:" + p.stub().getId(), "saved", p.match(), "Saved & invested", "savings", "🐖");
            }
            preTaxSaved += p.retirement() + p.match();
        }

        for (Map.Entry<Bucket, Long> e : b.income().entrySet()) {
            Bucket k = e.getKey();
            String id = "inc:" + key(k);
            s.node(id, k.name(), "income", k.categoryId(), k.icon());
            s.link(id, "income", e.getValue(), "Income", "income", "💰");
        }

        // Money taken out of savings, plus anything spent beyond income (paid from
        // savings not tracked here, or put on a card), feeds in on the left.
        long spent = b.spent();
        long savedFromCash = b.savedFromCash();
        long leftover = b.leftover();
        long fromSavings = b.withdrawn() + Math.max(0, -leftover);
        if (fromSavings > 0) {
            s.node("from-savings", "From savings or debt", "savings", null, "⤴️");
            s.link("from-savings", "income", fromSavings, "Income", "income", "💰");
        }

        s.link("income", "spending", spent, "Spending", "expense", "🧾");
        s.link("income", "saved", savedFromCash, "Saved & invested", "savings", "🐖");
        s.link("income", "leftover", leftover, "Left over", "income", "✨");

        int shown = 0;
        long other = 0;
        for (Map.Entry<Bucket, Long> e : b.expenses().entrySet()) {
            Bucket k = e.getKey();
            if (shown < MAX_EXPENSE_NODES || b.expenses().size() == MAX_EXPENSE_NODES + 1) {
                String id = "exp:" + key(k);
                s.node(id, k.name(), "expense", k.categoryId(), k.icon());
                s.link("spending", id, e.getValue(), k.name(), "expense", k.icon());
                shown++;
            } else {
                other += e.getValue();
            }
        }
        s.link("spending", "exp:other", other, "Other spending", "expense", "➕");

        // Pre-tax 401(k) and the employer match land in Retirement next to any
        // retirement contributions made from cash.
        Bucket retirementBucket = b.savings().keySet().stream()
                .filter(k -> ReferenceData.RETIREMENT.equals(k.name())).findFirst().orElse(null);
        for (Map.Entry<Bucket, Long> e : b.savings().entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            Bucket k = e.getKey();
            long value = e.getValue() + (k.equals(retirementBucket) ? preTaxSaved : 0);
            s.node("sav:" + key(k), k.name(), "savings", k.categoryId(), k.icon());
            s.link("saved", "sav:" + key(k), value, k.name(), "savings", k.icon());
        }
        if (retirementBucket == null || b.savings().get(retirementBucket) <= 0) {
            s.link("saved", "sav:retirement-pretax", preTaxSaved, "401(k)", "savings", "🏖️");
        }

        Double rate = b.savingsRate();
        return new Sankey(first.toString(), last.toString(), months, average, s.nodes(), s.links,
                b.grossIncome() / div, b.cashIncome() / div, spent / div, b.saved() / div,
                leftover / div, rate, !b.pay().isEmpty());
    }

    private static String key(Bucket b) {
        return b.categoryId() != null ? String.valueOf(b.categoryId()) : b.name().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /** Collects nodes and links, merging repeated links and dropping empty ones. */
    private static final class SankeyBuilder {
        private final long div;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, long[]> linkValues = new LinkedHashMap<>();
        final List<Link> links = new ArrayList<>();

        SankeyBuilder(long div) {
            this.div = div;
            node("income", "Income", "income", null, "💰");
            node("spending", "Spending", "expense", null, "🧾");
            node("saved", "Saved & invested", "savings", null, "🐖");
        }

        void node(String id, String label, String kind, Long categoryId, String icon) {
            nodes.putIfAbsent(id, new Node(id, label, kind, 0, categoryId, icon));
        }

        /** Adds value to source -> target, creating the target node if needed. */
        void link(String source, String target, long cents, String targetLabel, String targetKind, String icon) {
            if (cents <= 0) {
                return;
            }
            node(target, targetLabel, targetKind, null, icon);
            linkValues.computeIfAbsent(source + "\u0000" + target, k -> new long[1])[0] += cents;
        }

        List<Node> nodes() {
            Map<String, Long> in = new LinkedHashMap<>();
            Map<String, Long> out = new LinkedHashMap<>();
            linkValues.forEach((k, v) -> {
                String[] ends = k.split("\u0000");
                long value = v[0] / div;
                if (value <= 0) {
                    return;
                }
                links.add(new Link(ends[0], ends[1], value));
                out.merge(ends[0], value, Long::sum);
                in.merge(ends[1], value, Long::sum);
            });
            List<Node> result = new ArrayList<>();
            for (Node n : nodes.values()) {
                long value = Math.max(in.getOrDefault(n.id(), 0L), out.getOrDefault(n.id(), 0L));
                if (value > 0) {
                    result.add(new Node(n.id(), n.label(), n.kind(), value, n.categoryId(), n.icon()));
                }
            }
            return result;
        }
    }
}
