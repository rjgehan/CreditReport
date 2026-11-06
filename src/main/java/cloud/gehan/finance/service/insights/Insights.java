package cloud.gehan.finance.service.insights;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.Insight;
import cloud.gehan.finance.model.InsightItem;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.InsightItemRepository;
import cloud.gehan.finance.repo.InsightRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.ShortcutCategorizer;
import cloud.gehan.finance.service.assistant.AssistantTools;

/**
 * What Claude does with your money each day: a short take for Home, charges worth a
 * second look, the leftovers your rules missed, rules to suggest, and once-in-a-while
 * purchases (a mattress, a couch) to leave out of your monthly averages if you agree. Sundays add a
 * recap of the week; the start of a month adds a review of the last one and target
 * suggestions. "Can I afford it?" asks on demand.
 *
 * <p>Claude sees what {@link AssistantTools} returns: transactions and what's worked
 * out from them, never a balance. Anything that changes your setup (a rule, a
 * target) waits for you to approve it; only leftovers no rule or person filed are
 * filed directly, as the iPhone shortcut did.
 */
@Service
public class Insights {

    private static final Logger log = LoggerFactory.getLogger(Insights.class);
    static final int RECENT_DAYS = 45;
    /** How far back Claude looks for purchases that aren't part of a normal month. */
    static final int ONE_OFF_MONTHS = 6;

    static final String SYSTEM = """
            You are Claude, inside Finance, a personal finance app one person runs for themselves. You look over \
            their money and write short, useful notes for the app. They read them on an iPhone at a glance.

            By design you never see account balances, net worth or what investments are worth; don't guess them.

            How the data works: amounts are US dollars; in transactions negative is money out. Pay arrives after \
            taxes and 401(k), so income is take-home. Credit card payments and moves between their own accounts are \
            transfers, neither income nor spending. A one-off is a purchase they marked to keep out of their budget \
            (furniture after a move, say): mention it if it matters but never count it as usual spending. Rules \
            file transactions into categories, so a few may be in the wrong one.

            Every number you write must come from the data or be simple arithmetic on it. Round to whole dollars. \
            Write dates like "Nov 3". Be plain, direct and calm about money: no lectures, no cheerleading, no emoji, \
            no filler. Short sentences.""";

    private final ClaudeCode claude;
    private final AssistantTools tools;
    private final ShortcutCategorizer leftovers;
    private final InsightRepository insights;
    private final InsightItemRepository items;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TransactionRepository txns;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ZoneId zone;

    private volatile Instant lastRunAt;
    private volatile String lastError;

    public Insights(ClaudeCode claude, AssistantTools tools, ShortcutCategorizer leftovers, InsightRepository insights,
            InsightItemRepository items, CategoryRepository categories, CategoryRuleRepository rules,
            TransactionRepository txns, TransactionTemplate tx, ObjectMapper json, AppProperties props) {
        this.claude = claude;
        this.tools = tools;
        this.leftovers = leftovers;
        this.insights = insights;
        this.items = items;
        this.categories = categories;
        this.rules = rules;
        this.txns = txns;
        this.tx = tx;
        this.json = json;
        this.zone = props.zoneId();
    }

    public Instant lastRunAt() { return lastRunAt; }
    public String lastError() { return lastError; }

    // --- Every morning ------------------------------------------------------------------

    @Scheduled(cron = "${app.insights-cron:0 0 7 * * *}", zone = "${app.zone}")
    public void morning() {
        if (!claude.configured()) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Claude's morning run failed: {}", e.getMessage());
        }
    }

    /** Today's take (and on Sundays the week's recap), then last month's review if it's still missing. */
    public void refresh() {
        try {
            daily();
            YearMonth last = YearMonth.now(zone).minusMonths(1);
            if (insights.findByKindAndPeriod(Insight.Kind.MONTH, last.toString()).isEmpty()) {
                monthly(last);
            }
            lastError = null;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            throw e;
        } finally {
            lastRunAt = Instant.now();
        }
    }

    void daily() {
        LocalDate today = LocalDate.now(zone);
        boolean sunday = today.getDayOfWeek() == DayOfWeek.SUNDAY;
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        boolean recap = sunday && insights.findByKindAndPeriod(Insight.Kind.WEEK, weekStart.toString()).isEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today", today.toString());
        data.put("this_month", look("this_month", "{}"));
        data.put("months", look("monthly_summary", "{\"from_month\":\"" + YearMonth.from(today).minusMonths(3)
                + "\",\"to_month\":\"" + YearMonth.from(today) + "\"}"));
        data.put("targets_and_goals", look("spending_targets_and_goals", "{}"));
        data.put("monthly_expenses", look("monthly_expenses", "{}"));
        data.put("recent_transactions", look("find_transactions",
                "{\"from\":\"" + today.minusDays(RECENT_DAYS) + "\",\"limit\":200}"));
        data.put("to_file", toFile());
        data.put("categories", tx.execute(s -> leftovers.choices().stream().map(Category::getName).toList()));
        data.put("flagged_before", items.findByKind(InsightItem.Kind.CHARGE).stream()
                .map(InsightItem::getTransactionId).toList());
        // Every purchase of the last months, so a one-time buy can be told from a habit (and the
        // purchases from before this feature are looked at too).
        data.put("spending_history", look("find_transactions", "{\"kind\":\"spending\",\"from\":\""
                + today.minusMonths(ONE_OFF_MONTHS) + "\",\"limit\":200}"));
        data.put("one_off_suggested_before", items.findByKind(InsightItem.Kind.ONE_OFF).stream()
                .map(InsightItem::getTransactionId).toList());

        String ask = "Today is " + spoken(today) + ".\n\nWrite today's notes:\n"
                + "- status: on_track, watch or over, from this_month (its status and free_to_spend) and how "
                + "spending compares with their usual months.\n"
                + "- headline: one short sentence with the main thing, e.g. \"On track: $420 free for the rest of "
                + "November.\"\n"
                + "- notes: up to 3 short lines on what stands out, with numbers: a category well above usual (beer "
                + "and bars, say), a big one-off, a bill still due. Leave out anything unremarkable.\n"
                + "- odd_charges: recent transactions worth a second look: the same charge twice, a subscription or "
                + "bill that went up, a free trial that turned paid, a charge much bigger than usual for that "
                + "merchant. Use ids from recent_transactions and leave out ids in flagged_before. Usually empty.\n"
                + "- file: for each transaction in to_file you can recognise, the category it belongs in, from "
                + "categories, exactly as written. Leave out anything you'd have to guess, and payments to or from "
                + "a person unless the note makes it clear.\n"
                + "- rules: up to 3 rules worth adding, for merchants that keep landing uncategorized or in the wrong "
                + "category: pattern is a few words from the description that will match the next ones, category "
                + "exactly as written in categories. Usually empty.\n"
                + "- one_offs: purchases in spending_history that aren't part of a normal month and would distort "
                + "their monthly average: things bought once in years (a mattress, a couch, a desk, a laptop, an "
                + "appliance, the big furniture run after a move). Judge from the store, the amount against what they "
                + "usually spend there and in that category, and whether they buy there regularly; there is no fixed "
                + "minimum. Never rent, bills, loans, subscriptions, groceries, dining or anything they buy most "
                + "months. Leave out rows already marked oneOff and ids in one_off_suggested_before. Ids from "
                + "spending_history; reason is a few words, e.g. \"A mattress, bought every few years\". Usually empty.\n"
                + (recap ? "- week_recap: it's Sunday. Up to 3 short lines on this week (since " + shortDay(weekStart)
                        + "): what was up or down against usual, and one thing to watch.\n" : "")
                + "\nData:\n" + write(data);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", Map.of("type", "string", "enum", List.of("on_track", "watch", "over")));
        props.put("headline", Map.of("type", "string"));
        props.put("notes", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 3));
        props.put("odd_charges", array(Map.of("transaction_id", Map.of("type", "integer"),
                "reason", Map.of("type", "string")), List.of("transaction_id", "reason")));
        props.put("file", array(Map.of("transaction_id", Map.of("type", "integer"),
                "category", Map.of("type", "string")), List.of("transaction_id", "category")));
        props.put("rules", array(Map.of("pattern", Map.of("type", "string"), "category", Map.of("type", "string"),
                "reason", Map.of("type", "string")), List.of("pattern", "category", "reason")));
        props.put("one_offs", array(Map.of("transaction_id", Map.of("type", "integer"),
                "reason", Map.of("type", "string")), List.of("transaction_id", "reason")));
        List<String> required = new ArrayList<>(List.of("status", "headline", "notes", "odd_charges", "file", "rules",
                "one_offs"));
        if (recap) {
            props.put("week_recap", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 3));
            required.add("week_recap");
        }
        JsonNode a = claude.ask(SYSTEM, ask, object(props, required));

        tx.executeWithoutResult(s -> {
            Insight take = insights.findByKindAndPeriod(Insight.Kind.TAKE, today.toString())
                    .orElseGet(() -> new Insight(Insight.Kind.TAKE, today.toString()));
            take.setStatus(a.path("status").asText("watch"));
            take.setHeadline(clip(a.path("headline").asText(""), 300));
            take.setBody(clip(lines(a.path("notes")), 4000));
            take.setCreatedAt(Instant.now());
            insights.save(take);
            if (recap && a.has("week_recap")) {
                Insight w = new Insight(Insight.Kind.WEEK, weekStart.toString());
                w.setBody(clip(lines(a.path("week_recap")), 4000));
                insights.save(w);
            }
            flag(a.path("odd_charges"));
            suggestRules(a.path("rules"));
            suggestOneOffs(a.path("one_offs"));
        });
        file(a.path("file"));
    }

    void monthly(YearMonth m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("months", look("monthly_summary", "{\"from_month\":\"" + m.minusMonths(5) + "\",\"to_month\":\"" + m + "\"}"));
        data.put("targets_and_goals", look("spending_targets_and_goals", "{\"month\":\"" + m + "\"}"));
        data.put("monthly_expenses", look("monthly_expenses", "{}"));
        data.put("spending_categories", tx.execute(s -> categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> c.getKind() == CategoryKind.EXPENSE).map(Category::getName).toList()));
        String month = m.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US));
        String ask = "Write the review of " + month + ", the last month in months:\n"
                + "- review: up to 4 short lines on how it went against the months before it: income, spending, what "
                + "changed and why, one-offs. If it isn't a full month of history, say so in one line.\n"
                + "- targets: up to 4 spending targets to change or add, where the current monthly limit (or the lack "
                + "of one) doesn't fit how they actually spend: category exactly as in spending_categories, "
                + "monthly_limit in whole dollars, reason in one short sentence. Empty if the targets fit.\n"
                + "\nData:\n" + write(data);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("review", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 4));
        props.put("targets", array(Map.of("category", Map.of("type", "string"), "monthly_limit", Map.of("type", "integer"),
                "reason", Map.of("type", "string")), List.of("category", "monthly_limit", "reason")));
        JsonNode a = claude.ask(SYSTEM, ask, object(props, List.of("review", "targets")));
        tx.executeWithoutResult(s -> {
            Insight review = insights.findByKindAndPeriod(Insight.Kind.MONTH, m.toString())
                    .orElseGet(() -> new Insight(Insight.Kind.MONTH, m.toString()));
            review.setBody(clip(lines(a.path("review")), 4000));
            insights.save(review);
            // Last month's suggestions are stale now.
            items.findByKind(InsightItem.Kind.TARGET).stream().filter(i -> i.getState() == InsightItem.State.OPEN)
                    .forEach(items::delete);
            for (JsonNode t : a.path("targets")) {
                Optional<Category> c = categories.findByNameIgnoreCase(t.path("category").asText(""))
                        .filter(x -> x.getKind() == CategoryKind.EXPENSE);
                long limit = t.path("monthly_limit").asLong(0);
                if (c.isPresent() && limit > 0 && limit < 100_000) {
                    InsightItem i = new InsightItem(InsightItem.Kind.TARGET, clip(t.path("reason").asText(""), 500));
                    i.setCategoryId(c.get().getId());
                    i.setLimitCents(limit * 100);
                    items.save(i);
                }
            }
        });
    }

    // --- Can I afford it? ---------------------------------------------------------------

    public record Answer(String answer, String text) {
    }

    public Answer afford(String question) {
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today", today.toString());
        data.put("this_month", look("this_month", "{}"));
        data.put("months", look("monthly_summary", "{\"from_month\":\"" + YearMonth.from(today).minusMonths(2)
                + "\",\"to_month\":\"" + YearMonth.from(today) + "\"}"));
        data.put("targets_and_goals", look("spending_targets_and_goals", "{}"));
        data.put("monthly_expenses", look("monthly_expenses", "{}"));
        data.put("recent_transactions", look("find_transactions", "{\"from\":\"" + today.minusDays(30) + "\",\"limit\":100}"));
        String ask = "Today is " + spoken(today) + ". They ask: \"" + question.replace("\"", "'") + "\"\n\n"
                + "Answer from this_month: what's free to spend after bills and their savings target (free_to_spend, "
                + "free_per_day, free_per_week), what's still due, and what they usually spend from here to the end of "
                + "the month. answer is yes, careful or no. text is 1 to 3 short sentences with the numbers that "
                + "decide it. If it isn't about spending money, answer careful and say what you can help with.\n"
                + "\nData:\n" + write(data);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("answer", Map.of("type", "string", "enum", List.of("yes", "careful", "no")));
        props.put("text", Map.of("type", "string"));
        JsonNode a = claude.ask(SYSTEM, ask, object(props, List.of("answer", "text")));
        return new Answer(a.path("answer").asText("careful"), a.path("text").asText(""));
    }

    // --- Applying answers ---------------------------------------------------------------

    private List<Map<String, Object>> toFile() {
        return tx.execute(s -> leftovers.leftovers().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("date", t.getDay().toString());
            m.put("amount", BigDecimal.valueOf(t.getAmountCents(), 2));
            m.put("description", t.matchText());
            if (t.getNotes() != null && !t.getNotes().isBlank()) {
                m.put("note", t.getNotes());
            }
            return m;
        }).toList());
    }

    /** Through the shortcut's own checks: only leftovers, never something a rule or you chose. */
    private void file(JsonNode picks) {
        StringBuilder reply = new StringBuilder();
        for (JsonNode p : picks) {
            reply.append(p.path("transaction_id").asLong()).append(" | ").append(p.path("category").asText()).append('\n');
        }
        if (!reply.isEmpty()) {
            log.info("Claude filed leftovers: {}", leftovers.answer(reply.toString()));
        }
    }

    private void flag(JsonNode charges) {
        Set<Long> known = new HashSet<>();
        items.findByKind(InsightItem.Kind.CHARGE).forEach(i -> known.add(i.getTransactionId()));
        int added = 0;
        for (JsonNode c : charges) {
            long id = c.path("transaction_id").asLong(-1);
            Optional<Transaction> t = txns.findById(id).filter(x -> !x.getAccount().isHidden());
            if (t.isPresent() && known.add(id) && added++ < 5) {
                InsightItem i = new InsightItem(InsightItem.Kind.CHARGE, clip(c.path("reason").asText(""), 500));
                i.setTransactionId(id);
                items.save(i);
            }
        }
    }

    /**
     * Only real purchases that still count toward the averages, and never rent, loans or other fixed
     * costs: those are monthly by nature. Each purchase is suggested once; skipping it is final.
     */
    private void suggestOneOffs(JsonNode suggested) {
        Set<Long> known = new HashSet<>();
        items.findByKind(InsightItem.Kind.ONE_OFF).forEach(i -> known.add(i.getTransactionId()));
        for (JsonNode o : suggested) {
            long id = o.path("transaction_id").asLong(-1);
            Optional<Transaction> t = txns.findById(id).filter(x -> !x.getAccount().isHidden() && x.getAmountCents() < 0
                    && !x.isOneOff() && x.getCategory() != null && x.getCategory().getKind() == CategoryKind.EXPENSE
                    && !x.getCategory().isFixedCost());
            if (t.isPresent() && known.add(id)) {
                InsightItem i = new InsightItem(InsightItem.Kind.ONE_OFF, clip(o.path("reason").asText(""), 500));
                i.setTransactionId(id);
                items.save(i);
            }
        }
    }

    private void suggestRules(JsonNode suggested) {
        Set<String> known = new HashSet<>();
        rules.findAll().forEach(r -> known.add(r.getPattern().toUpperCase(Locale.ROOT)));
        items.findByKind(InsightItem.Kind.RULE).forEach(i -> known.add(i.getPattern()));
        for (JsonNode r : suggested) {
            String pattern = r.path("pattern").asText("").trim().toUpperCase(Locale.ROOT);
            Optional<Category> c = categories.findByNameIgnoreCase(r.path("category").asText(""));
            if (pattern.length() >= 3 && pattern.length() <= 60 && c.isPresent() && known.add(pattern)) {
                InsightItem i = new InsightItem(InsightItem.Kind.RULE, clip(r.path("reason").asText(""), 500));
                i.setPattern(pattern);
                i.setCategoryId(c.get().getId());
                items.save(i);
            }
        }
    }

    // --- Helpers ------------------------------------------------------------------------

    private JsonNode look(String tool, String input) {
        try {
            return json.readTree(tools.run(tool, json.readTree(input)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> object(Map<String, Object> props, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> itemProps, List<String> required) {
        return Map.of("type", "array", "items", object(new LinkedHashMap<>(itemProps), required));
    }

    private static String lines(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(l -> {
            String s = l.asText("").strip();
            if (!s.isEmpty()) {
                out.add(s.startsWith("- ") ? s.substring(2) : s);
            }
        });
        return String.join("\n", out);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String spoken(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US));
    }

    static String shortDay(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
    }
}
