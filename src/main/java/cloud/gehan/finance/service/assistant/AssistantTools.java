package cloud.gehan.finance.service.assistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRole;
import cloud.gehan.finance.model.GoalType;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.BillService;
import cloud.gehan.finance.service.FlowService;
import cloud.gehan.finance.service.ForecastService;
import cloud.gehan.finance.service.PaycheckService;
import cloud.gehan.finance.service.RecurringService;
import cloud.gehan.finance.service.TargetService;
import jakarta.persistence.criteria.Predicate;

/**
 * What Claude can look at, over MCP: transactions and everything worked out from
 * them, read only. Nothing here returns a balance, net worth or an investment's
 * value; a goal that tracks a balance reports only how far along it is.
 */
@Component
public class AssistantTools {

    private static final int MAX_ROWS = 200;
    private static final int MAX_MONTHS = 12;

    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final FlowService flow;
    private final ForecastService forecast;
    private final TargetService targets;
    private final RecurringService recurring;
    private final BillService bills;
    private final PaycheckService paychecks;
    private final ObjectMapper json;
    private final ZoneId zone;

    public AssistantTools(TransactionRepository txns, CategoryRepository categories, AccountRepository accounts,
            FlowService flow, ForecastService forecast, TargetService targets, RecurringService recurring,
            BillService bills, PaycheckService paychecks, ObjectMapper json, AppProperties props) {
        this.txns = txns;
        this.categories = categories;
        this.accounts = accounts;
        this.flow = flow;
        this.forecast = forecast;
        this.targets = targets;
        this.recurring = recurring;
        this.bills = bills;
        this.paychecks = paychecks;
        // Leaving out empty fields keeps the results (and the bill) small.
        this.json = json.copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        this.zone = props.zoneId();
    }

    /** A tool call that can't be answered as asked; its message goes back to Claude. */
    public static class BadInput extends RuntimeException {
        public BadInput(String message) {
            super(message);
        }
    }

    // --- Definitions --------------------------------------------------------------------

    /** A tool as MCP lists it: its name, what it does, and a JSON Schema for its input. */
    public record Definition(String name, String description, Map<String, Object> inputSchema) {
    }

    public List<Definition> definitions() {
        return List.of(
                tool("find_transactions",
                        "Search transactions, newest first. Every filter is optional and they combine. Returns the "
                                + "matching rows (up to limit) plus the count and money in/out across all matches, so "
                                + "totals are right even when not every row is shown. Hidden accounts are left out.",
                        props(
                                "query", str("Words to look for in the description, merchant or note, e.g. \"venmo\" or \"trader joe\""),
                                "category", str("A category name from list_categories_and_accounts, or \"Uncategorized\""),
                                "account", str("An account name from list_categories_and_accounts"),
                                "kind", enumOf("income, spending, savings (moves into savings and investing) or transfer",
                                        "income", "spending", "savings", "transfer"),
                                "from", str("First day, YYYY-MM-DD"),
                                "to", str("Last day, YYYY-MM-DD"),
                                "min_amount", num("Only rows at least this many dollars, either direction"),
                                "max_amount", num("Only rows at most this many dollars, either direction"),
                                "limit", num("How many rows to return, 1-200, default 50")),
                        List.of()),
                tool("monthly_summary",
                        "Money in and out for whole calendar months: income by category, spending by category "
                                + "(refunds netted in), money moved into savings and investing, and one-off purchases "
                                + "the user marked to keep out of their budget. The current month is month to date.",
                        props(
                                "from_month", str("First month, YYYY-MM"),
                                "to_month", str("Last month, YYYY-MM; at most 12 months after from_month. Defaults to from_month")),
                        List.of("from_month")),
                tool("this_month",
                        "The current month looking ahead: pay received and still coming, spending so far by category, "
                                + "bills still due, the savings target, what is free to spend after all of that, the "
                                + "usual everyday spending from here to month end, and a status.",
                        props(), List.of()),
                tool("spending_targets_and_goals",
                        "Spending targets (monthly limits per category) with what has been spent against each, and "
                                + "savings goals with their progress, for one month.",
                        props("month", str("YYYY-MM, default the current month")),
                        List.of()),
                tool("monthly_expenses",
                        "Recurring charges found in the transactions plus any added by hand: subscriptions, bills, "
                                + "utilities. Each has its amount, how often, what it comes to a month, and when it was "
                                + "last and is next charged. Also lists ones that seem to have stopped.",
                        props(), List.of()),
                tool("rent_and_loans",
                        "Rent and loan payments: per bill, paid this year, paid all time, since when, and month by month.",
                        props("months", num("How many months back to list per bill, 1-36, default 12")),
                        List.of()),
                tool("paychecks",
                        "Pay deposits: the paychecks the app recognizes (who from, how often, usual amount, next "
                                + "expected) and pay received per month.",
                        props(), List.of()),
                tool("list_categories_and_accounts",
                        "The category names (with kind: income, expense, savings or transfer) and the account names "
                                + "and types, for filtering find_transactions. No balances.",
                        props(), List.of()));
    }

    private static Definition tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return new Definition(name, description, schema);
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> num(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> enumOf(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    // --- Running ------------------------------------------------------------------------

    /**
     * Runs one tool and returns its result as JSON text. Each call is its own short
     * read-only transaction: the conversation around it waits on Claude for seconds
     * at a time and must not hold the database's one connection meanwhile.
     */
    @Transactional(readOnly = true)
    public String run(String name, JsonNode input) {
        Object result = switch (name) {
            case "find_transactions" -> findTransactions(input);
            case "monthly_summary" -> monthlySummary(input);
            case "this_month" -> thisMonth();
            case "spending_targets_and_goals" -> targetsAndGoals(input);
            case "monthly_expenses" -> monthlyExpenses();
            case "rent_and_loans" -> rentAndLoans(input);
            case "paychecks" -> paychecks();
            case "list_categories_and_accounts" -> categoriesAndAccounts();
            default -> throw new BadInput("No tool called " + name);
        };
        try {
            return json.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Transactions -------------------------------------------------------------------

    record Row(LocalDate date, String description, String merchant, BigDecimal amount, String category,
            String account, String note, Boolean oneOff, Boolean pending) {
    }

    private Map<String, Object> findTransactions(JsonNode in) {
        String query = text(in, "query");
        Category category = category(text(in, "category"));
        boolean uncategorized = category != null && category.hasRole(CategoryRole.UNCATEGORIZED);
        Account account = account(text(in, "account"));
        CategoryKind kind = switch (Optional.ofNullable(text(in, "kind")).orElse("").toLowerCase(Locale.ROOT)) {
            case "" -> null;
            case "income" -> CategoryKind.INCOME;
            case "spending", "expense", "expenses" -> CategoryKind.EXPENSE;
            case "savings", "saving", "investing" -> CategoryKind.SAVINGS;
            case "transfer", "transfers" -> CategoryKind.TRANSFER;
            default -> throw new BadInput("kind must be income, spending, savings or transfer");
        };
        LocalDate from = day(in, "from");
        LocalDate to = day(in, "to");
        Long min = cents(in, "min_amount");
        Long max = cents(in, "max_amount");
        int limit = (int) Math.max(1, Math.min(MAX_ROWS, number(in, "limit", 50)));

        Specification<Transaction> spec = (root, q, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isFalse(root.get("account").get("hidden")));
            if (account != null) {
                where.add(cb.equal(root.get("account"), account));
            }
            if (uncategorized) {
                where.add(cb.or(cb.isNull(root.get("category")), cb.equal(root.get("category"), category)));
            } else if (category != null) {
                where.add(cb.equal(root.get("category"), category));
            }
            if (kind != null) {
                where.add(cb.equal(root.get("category").get("kind"), kind));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("day"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("day"), to));
            }
            if (query != null) {
                String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("merchant"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), like)));
            }
            // Either direction: a $40 refund and a $40 purchase are both "about $40".
            var size = cb.abs(root.<Long>get("amountCents"));
            if (min != null) {
                where.add(cb.greaterThanOrEqualTo(size, Math.abs(min)));
            }
            if (max != null) {
                where.add(cb.lessThanOrEqualTo(size, Math.abs(max)));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };

        long moneyIn = 0;
        long moneyOut = 0;
        long count = 0;
        for (Transaction t : txns.findAll(spec)) {
            count++;
            if (t.getAmountCents() > 0) {
                moneyIn += t.getAmountCents();
            } else {
                moneyOut -= t.getAmountCents();
            }
        }
        List<Row> rows = txns.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Order.desc("day"), Sort.Order.desc("id"))))
                .stream().map(AssistantTools::row).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", count);
        result.put("money_in", usd(moneyIn));
        result.put("money_out", usd(moneyOut));
        result.put("shown", rows.size());
        if (rows.size() < count) {
            result.put("note", "Only the newest " + rows.size() + " rows are listed; the totals cover all " + count + ".");
        }
        txns.earliestDay().ifPresent(d -> result.put("history_starts", d));
        result.put("transactions", rows);
        return result;
    }

    private static Row row(Transaction t) {
        Category c = t.getCategory();
        String merchant = t.getMerchant() == null || t.getMerchant().equalsIgnoreCase(t.getDescription()) ? null : t.getMerchant();
        return new Row(t.getDay(), t.getDescription(), merchant, usd(t.getAmountCents()),
                c == null ? "Uncategorized" : c.getName(), t.getAccount().getName(),
                t.getNotes() == null || t.getNotes().isBlank() ? null : t.getNotes(),
                t.isOneOff() ? true : null, t.isPending() ? true : null);
    }

    // --- Months -------------------------------------------------------------------------

    record Line(String category, BigDecimal amount) {
    }

    record MonthTotals(String month, boolean monthToDate, BigDecimal income, List<Line> incomeByCategory,
            BigDecimal spent, List<Line> spendingByCategory, BigDecimal oneOffs, BigDecimal movedToSavings,
            BigDecimal takenFromSavings, List<Line> savingsByCategory, BigDecimal pretaxRetirement,
            BigDecimal leftOver) {
    }

    private Map<String, Object> monthlySummary(JsonNode in) {
        YearMonth first = month(in, "from_month");
        if (first == null) {
            throw new BadInput("from_month is required, as YYYY-MM");
        }
        YearMonth last = Optional.ofNullable(month(in, "to_month")).orElse(first);
        YearMonth now = YearMonth.now(zone);
        if (last.isAfter(now)) {
            last = now;
        }
        if (last.isBefore(first)) {
            throw new BadInput("to_month is before from_month");
        }
        if (first.plusMonths(MAX_MONTHS - 1).isBefore(last)) {
            throw new BadInput("At most " + MAX_MONTHS + " months at a time");
        }
        List<MonthTotals> months = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            FlowService.Breakdown b = flow.month(m);
            months.add(new MonthTotals(m.toString(), m.equals(now), usd(b.cashIncome()), income(b),
                    usd(b.spent()), lines(b.expenses()), usd(b.oneOffCents()), usd(b.savedFromCash()),
                    usd(b.withdrawn()), lines(b.savings()), usd(b.savedPreTax()), usd(b.leftover())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        txns.earliestDay().ifPresent(d -> result.put("history_starts", d));
        result.put("notes", "income is take-home (paychecks arrive after taxes and 401(k)). spent leaves out one-offs "
                + "and transfers between the user's own accounts. left_over = income + taken_from_savings - spent - "
                + "one_offs - moved_to_savings; negative means the month ran on savings or credit.");
        result.put("months", months);
        return result;
    }

    private static List<Line> income(FlowService.Breakdown b) {
        Map<String, Long> by = new LinkedHashMap<>();
        b.income().forEach((k, v) -> by.merge(k.name(), v, Long::sum));
        // Paychecks with a paystub are kept apart (to show taxes); fold them back in.
        b.pay().forEach(p -> by.merge("Paycheck", p.netCents(), Long::sum));
        return by.entrySet().stream().filter(e -> e.getValue() != 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new Line(e.getKey(), usd(e.getValue()))).toList();
    }

    private static List<Line> lines(Map<FlowService.Bucket, Long> buckets) {
        return buckets.entrySet().stream().filter(e -> e.getValue() != 0)
                .sorted(Map.Entry.<FlowService.Bucket, Long>comparingByValue(Comparator.comparingLong(Math::abs)).reversed())
                .map(e -> new Line(e.getKey().name(), usd(e.getValue()))).toList();
    }

    // --- This month ---------------------------------------------------------------------

    record Coming(String name, LocalDate day, BigDecimal amount) {
    }

    private Map<String, Object> thisMonth() {
        ForecastService.Forecast f = forecast.forecast();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", f.month());
        r.put("day_of_month", f.dayOfMonth());
        r.put("days_left", f.daysLeft());
        r.put("pay_received", usd(f.receivedCents()));
        r.put("pay_still_coming", f.incomeComing().stream().map(c -> new Coming(c.name(), c.day(), usd(c.cents()))).toList());
        r.put("pay_this_month", usd(f.incomeCents()));
        r.put("spent_so_far", usd(f.spentCents()));
        r.put("spent_by_category", f.spentByCategory().stream().map(s -> new Line(s.name(), usd(s.cents()))).toList());
        r.put("one_offs_left_out", usd(f.oneOffCents()));
        r.put("bills_still_due", f.billsComing().stream().map(c -> new Coming(c.name(), c.day(), usd(c.cents()))).toList());
        r.put("bills_still_due_total", usd(f.billsComingCents()));
        r.put("savings_target", usd(f.savingsTargetCents()));
        r.put("room", usd(f.roomCents()));
        r.put("free_to_spend", usd(f.freeCents()));
        r.put("free_per_day", usd(f.perDayCents()));
        r.put("free_per_week", usd(f.perWeekCents()));
        r.put("usual_spending_rest_of_month", usd(f.usualRestCents()));
        r.put("likely_left_at_month_end", usd(f.endCents()));
        r.put("usual_pace_from_own_history", f.history());
        r.put("status", f.status());
        r.put("notes", "room = pay_this_month - spent_so_far - bills_still_due_total. free_to_spend = room - "
                + "savings_target, never below 0. likely_left_at_month_end = free_to_spend - "
                + "usual_spending_rest_of_month. status: ok; tight (the usual pace from here needs more than is free); "
                + "under (the full savings target doesn't fit); short (spending and bills exceed pay); unknown (no pay "
                + "known yet this month).");
        return r;
    }

    // --- Targets and goals --------------------------------------------------------------

    record Target(String category, BigDecimal monthlyLimit, BigDecimal spent, int percentUsed,
            Integer percentOfMonthGone, String status) {
    }

    record Goal(String name, String type, BigDecimal target, Double targetPercent, String percentDone,
            Double currentPercent, BigDecimal thisMonth, LocalDate by, BigDecimal neededPerMonth, String status,
            String detail) {
    }

    private Map<String, Object> targetsAndGoals(JsonNode in) {
        YearMonth m = Optional.ofNullable(month(in, "month")).orElse(YearMonth.now(zone));
        TargetService.Progress p = targets.progress(m);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", p.month());
        r.put("targets", p.spending().stream().map(s -> new Target(s.name(), usd(s.limitCents()), usd(s.spentCents()),
                (int) Math.round(s.fraction() * 100),
                s.expectedFraction() == null ? null : (int) Math.round(s.expectedFraction() * 100), s.status())).toList());
        r.put("budgeted_total", usd(p.budgetedCents()));
        r.put("spent_in_targets_total", usd(p.spentInBudgetsCents()));
        r.put("goals", p.goals().stream().map(AssistantTools::goal).toList());
        return r;
    }

    private static Goal goal(TargetService.GoalProgress g) {
        String done = Math.round(g.fraction() * 100) + "%";
        if (GoalType.BALANCE.name().equals(g.type())) {
            // Its amounts are an account balance (and the target plus progress would give it away).
            return new Goal(g.name(), "grow savings to an amount", null, null, done, null, null, g.targetDate(), null,
                    g.status(), g.detail());
        }
        boolean rate = GoalType.SAVINGS_RATE.name().equals(g.type());
        return new Goal(g.name(), rate ? "savings rate each month" : "save an amount each month",
                g.targetCents() == null ? null : usd(g.targetCents()), g.targetPercent(), done,
                g.currentPercent() == null ? null : Math.round(g.currentPercent() * 10) / 10.0,
                usd(g.currentCents()), null, null, g.status(), g.detail());
    }

    // --- Recurring, bills, pay ----------------------------------------------------------

    record Charge(String name, String kind, String cadence, BigDecimal amount, BigDecimal perMonth, LocalDate last,
            LocalDate next, String category, String account) {
    }

    private Map<String, Object> monthlyExpenses() {
        RecurringService.Overview o = recurring.overview();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total_per_month", usd(o.monthlyCents()));
        r.put("charges", o.items().stream().map(AssistantTools::charge).toList());
        r.put("seem_stopped", o.stopped().stream().map(AssistantTools::charge).toList());
        return r;
    }

    private static Charge charge(RecurringService.Item i) {
        return new Charge(i.name(), i.kind() == null ? null : i.kind().name().toLowerCase(Locale.ROOT), i.cadence(),
                usd(i.amountCents()), usd(i.monthlyCents()), i.lastDate(), i.nextDate(), i.categoryName(), i.accountName());
    }

    record Paid(String month, BigDecimal paid, int payments) {
    }

    record Bill(String name, BigDecimal thisYear, BigDecimal allTime, LocalDate since, LocalDate lastPaid,
            BigDecimal lastPayment, List<Paid> months) {
    }

    private Map<String, Object> rentAndLoans(JsonNode in) {
        int months = (int) Math.max(1, Math.min(36, number(in, "months", 12)));
        BillService.Bills b = bills.bills(months);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bank_history_starts", b.historyStart());
        r.put("bills", b.bills().stream().map(x -> new Bill(x.name(), usd(x.thisYearCents()), usd(x.allTimeCents()),
                x.since(), x.lastPaid(), usd(x.lastPaidCents()),
                x.months().stream().map(m -> new Paid(m.month(), usd(m.paidCents()), m.count())).toList())).toList());
        return r;
    }

    record Payer(String name, String frequency, int deposits, BigDecimal usual, BigDecimal perMonth, LocalDate last,
            LocalDate nextExpected, boolean tracked) {
    }

    private Map<String, Object> paychecks() {
        PaycheckService.Overview o = paychecks.overview();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("average_per_month", usd(o.averageMonthlyCents()));
        r.put("payers", o.detected().stream().filter(d -> !d.accountHidden())
                .map(d -> new Payer(d.pattern(), d.frequency(), d.count(), usd(d.averageCents()),
                        usd(d.monthlyEstimateCents()), d.lastDate(), d.nextExpected(), d.tracked())).toList());
        r.put("by_month", o.byMonth().stream().map(m -> new Paid(m.month(), usd(m.totalCents()), m.count())).toList());
        r.put("notes", "Amounts are take-home: taxes and 401(k) come out before pay reaches the bank. tracked means "
                + "the app files these deposits as pay.");
        return r;
    }

    record Cat(String name, String kind) {
    }

    record Acct(String name, String type, String institution) {
    }

    private Map<String, Object> categoriesAndAccounts() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("categories", categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> new Cat(c.getName(), c.getKind().name().toLowerCase(Locale.ROOT))).toList());
        r.put("accounts", accounts.findByHiddenFalse().stream()
                .map(a -> new Acct(a.getName(), a.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '), a.getInstitution()))
                .toList());
        return r;
    }

    // --- Input helpers ------------------------------------------------------------------

    private Category category(String name) {
        if (name == null) {
            return null;
        }
        Optional<Category> found = categories.findByNameIgnoreCase(name);
        if (found.isEmpty() && name.equalsIgnoreCase("uncategorized")) {
            found = categories.findByRole(CategoryRole.UNCATEGORIZED);
        }
        return found.orElseThrow(() -> new BadInput("No category called \"" + name + "\". Categories: "
                + String.join(", ", categories.findAllByOrderBySortOrderAscNameAsc().stream().map(Category::getName).toList())));
    }

    private Account account(String name) {
        if (name == null) {
            return null;
        }
        List<Account> visible = accounts.findByHiddenFalse();
        return visible.stream().filter(a -> a.getName().equalsIgnoreCase(name)).findFirst()
                .or(() -> visible.stream().filter(a -> a.getName().toLowerCase(Locale.ROOT)
                        .contains(name.toLowerCase(Locale.ROOT))).findFirst())
                .orElseThrow(() -> new BadInput("No account called \"" + name + "\". Accounts: "
                        + String.join(", ", visible.stream().map(Account::getName).toList())));
    }

    private static String text(JsonNode in, String field) {
        JsonNode v = in == null ? null : in.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static double number(JsonNode in, String field, double fallback) {
        JsonNode v = in == null ? null : in.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText().replace("$", "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new BadInput(field + " must be a number");
        }
    }

    private static Long cents(JsonNode in, String field) {
        JsonNode v = in == null ? null : in.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return Math.round(number(in, field, 0) * 100);
    }

    private static LocalDate day(JsonNode in, String field) {
        String s = text(in, field);
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new BadInput(field + " must be a date as YYYY-MM-DD");
        }
    }

    private static YearMonth month(JsonNode in, String field) {
        String s = text(in, field);
        try {
            return s == null ? null : YearMonth.parse(s.length() > 7 ? s.substring(0, 7) : s);
        } catch (DateTimeParseException e) {
            throw new BadInput(field + " must be a month as YYYY-MM");
        }
    }

    static BigDecimal usd(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
