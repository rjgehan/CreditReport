package cloud.gehan.finance.service.insights;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.Question;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.QuestionRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.assistant.AssistantTools;
import jakarta.annotation.PreDestroy;

/**
 * The Ask box: anything about your money, answered by Claude from the whole history at
 * once ("How much did the move cost all in?", "Can I afford a $200 concert?"). It runs in
 * the background and the answer is kept, so locking the phone mid-answer loses nothing;
 * the page checks back. Same rules as the morning notes: no balances, no tools.
 */
@Service
public class Ask {

    private static final Logger log = LoggerFactory.getLogger(Ask.class);

    /** Enough for years of this user's history; the oldest rows past it are left out. */
    static final int LEDGER_ROWS = 4000;
    /** A backstop: nothing takes this long, even waiting behind another run. */
    static final Duration GIVE_UP = Duration.ofHours(1);

    /** The answering queue lives in memory: a question asked before this start can't still be running. */
    private final Instant bootedAt = Instant.now();

    private final ClaudeCode claude;
    private final Insights insights;
    private final AssistantTools tools;
    private final QuestionRepository questions;
    private final CategoryRepository categories;
    private final TransactionRepository txns;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claude-ask");
        t.setDaemon(true);
        return t;
    });

    public Ask(ClaudeCode claude, Insights insights, AssistantTools tools, QuestionRepository questions,
            CategoryRepository categories, TransactionRepository txns, TransactionTemplate tx, AppProperties props) {
        this.claude = claude;
        this.insights = insights;
        this.tools = tools;
        this.questions = questions;
        this.categories = categories;
        this.txns = txns;
        this.tx = tx;
        this.zone = props.zoneId();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Saves the question and starts answering it; returns at once. One question at a time. */
    public Question start(String text) {
        if (!claude.configured()) {
            throw new ClaudeCode.Problem("Claude isn't set up yet: Accounts, More, Claude.");
        }
        Question last = questions.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (last != null && Question.RUNNING.equals(last.getStatus()) && !abandoned(last)) {
            throw new ClaudeCode.Problem("Claude is still answering your last question.");
        }
        Question q = questions.save(new Question(text));
        worker.submit(() -> answer(q.getId()));
        return q;
    }

    /** Still RUNNING from before this start (the restart cut it off), or for longer than anything takes. */
    public boolean abandoned(Question q) {
        return Question.RUNNING.equals(q.getStatus())
                && (q.getCreatedAt().isBefore(bootedAt) || q.getCreatedAt().isBefore(Instant.now().minus(GIVE_UP)));
    }

    void answer(Long id) {
        Question q = questions.findById(id).orElse(null);
        if (q == null) {
            return;
        }
        try {
            JsonNode a = claude.ask(Insights.SYSTEM + ANSWERING, prompt(q.getText()), schema());
            List<String> lines = new ArrayList<>();
            a.path("lines").forEach(l -> {
                String s = l.asText("").strip();
                if (!s.isEmpty()) {
                    lines.add(s.startsWith("- ") ? s.substring(2) : s);
                }
            });
            List<String> ids = new ArrayList<>();
            a.path("transaction_ids").forEach(t -> {
                if (t.canConvertToLong() && ids.size() < 100) {
                    ids.add(String.valueOf(t.asLong()));
                }
            });
            String verdict = a.path("verdict").asText("none");
            tx.executeWithoutResult(s -> {
                Question done = questions.findById(id).orElseThrow();
                done.setStatus(lines.isEmpty() ? Question.FAILED : Question.DONE);
                done.setError(lines.isEmpty() ? "Claude didn't answer. Try asking it another way." : null);
                done.setLines(clip(String.join("\n", lines), 4000));
                done.setTransactionIds(clip(String.join(",", ids), 2000));
                done.setVerdict(verdict);
                done.setAnsweredAt(Instant.now());
                questions.save(done);
            });
        } catch (RuntimeException e) {
            if (!(e instanceof ClaudeCode.Problem)) {
                log.warn("Answering question {} failed", id, e);
            }
            String why = e instanceof ClaudeCode.Problem ? e.getMessage() : "Something went wrong. Try again.";
            try {
                tx.executeWithoutResult(s -> questions.findById(id).ifPresent(failed -> {
                    failed.setStatus(Question.FAILED);
                    failed.setError(clip(why, 500));
                    failed.setAnsweredAt(Instant.now());
                    questions.save(failed);
                }));
            } catch (RuntimeException f) {
                log.warn("Couldn't record that question {} failed", id, f);
            }
        }
    }

    static final String ANSWERING = """


            Now they've typed a question in the app's Ask box. Answer it from the data: the ledger has every \
            transaction you may see, and the rest is what the app works out from them. Lead with the answer; then \
            only the numbers that decide it. If the data can't answer it, say what's missing.""";

    String prompt(String question) {
        LocalDate today = LocalDate.now(zone);
        YearMonth now = YearMonth.from(today);
        YearMonth first = txns.earliestDay().map(YearMonth::from).orElse(now);
        if (first.isBefore(now.minusMonths(11))) {
            first = now.minusMonths(11);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today", today.toString());
        data.put("accounts_behind", insights.accountsBehind());
        data.put("this_month", insights.look("this_month", "{}"));
        data.put("months", insights.look("monthly_summary",
                "{\"from_month\":\"" + first + "\",\"to_month\":\"" + now + "\"}"));
        data.put("targets_and_goals", insights.look("spending_targets_and_goals", "{}"));
        data.put("monthly_expenses", insights.look("monthly_expenses", "{}"));
        data.put("rent_and_loans", insights.look("rent_and_loans", "{\"months\":12}"));
        data.put("paychecks", insights.look("paychecks", "{}"));
        data.put("categories", tx.execute(s -> categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(Category::getName).toList()));
        return "Today is " + Insights.spoken(today) + ". They ask: \"" + question.replace("\"", "'") + "\"\n\n"
                + "- lines: 1 to 5 short lines. The answer first, then only what decides it, with numbers.\n"
                + "- transaction_ids: the transactions your answer is about or adds up, from the ledger, so the app "
                + "can list them and total them itself. Empty if none.\n"
                + "- verdict: yes, careful or no when they ask whether they can afford or should buy something "
                + "(judge it from this_month: free_to_spend, what's still due, and their usual spending for the rest "
                + "of the month); otherwise none. If accounts_behind isn't empty, spending on those accounts since "
                + "that day is missing, so free_to_spend reads too high: say so, and answer careful rather than yes.\n"
                + "\nData:\n" + insights.write(data) + "\n\nLedger:\n" + tools.ledger(LEDGER_ROWS);
    }

    static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lines", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 5));
        props.put("transaction_ids", Map.of("type", "array", "items", Map.of("type", "integer"), "maxItems", 100));
        props.put("verdict", Map.of("type", "string", "enum", List.of("yes", "careful", "no", "none")));
        return Insights.object(props, List.of("lines", "transaction_ids", "verdict"));
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
