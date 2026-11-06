package cloud.gehan.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.Insight;
import cloud.gehan.finance.model.InsightItem;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.InsightItemRepository;
import cloud.gehan.finance.repo.InsightRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.insights.ClaudeCode;
import cloud.gehan.finance.service.insights.Insights;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Claude's notes for Home, its flags and suggestions, "Can I afford it?", and its sign-in. */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final Insights insights;
    private final ClaudeCode claude;
    private final InsightRepository notes;
    private final InsightItemRepository items;
    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final TransactionTemplate tx;

    public InsightsController(Insights insights, ClaudeCode claude, InsightRepository notes, InsightItemRepository items,
            TransactionRepository txns, CategoryRepository categories, TransactionTemplate tx) {
        this.insights = insights;
        this.claude = claude;
        this.notes = notes;
        this.items = items;
        this.txns = txns;
        this.categories = categories;
        this.tx = tx;
    }

    public record Note(String period, String status, String headline, List<String> lines, Instant at) {
        static Note of(Insight i) {
            return i == null ? null : new Note(i.getPeriod(), i.getStatus(), i.getHeadline(),
                    i.getBody() == null || i.getBody().isBlank() ? List.of() : List.of(i.getBody().split("\n")),
                    i.getCreatedAt());
        }
    }

    /**
     * An open item, with what the screen needs to show and approve it: the charge's
     * date, description and amount, or the rule's pattern and category, or the target.
     */
    public record Item(Long id, String kind, String reason, Long transactionId, LocalDate day, String description,
            Long amountCents, String pattern, Long categoryId, String categoryName, Long limitCents) {
    }

    @GetMapping
    public Map<String, Object> overview() {
        return tx.execute(s -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("configured", claude.configured());
            out.put("busy", claude.busy());
            out.put("lastRunAt", insights.lastRunAt());
            out.put("lastError", insights.lastError());
            out.put("take", Note.of(notes.findFirstByKindOrderByCreatedAtDesc(Insight.Kind.TAKE).orElse(null)));
            out.put("week", Note.of(notes.findFirstByKindOrderByCreatedAtDesc(Insight.Kind.WEEK).orElse(null)));
            List<Item> open = new ArrayList<>();
            for (InsightItem i : items.findByStateOrderByCreatedAtDesc(InsightItem.State.OPEN)) {
                Transaction t = i.getTransactionId() == null ? null : txns.findById(i.getTransactionId()).orElse(null);
                Category c = i.getCategoryId() == null ? null : categories.findById(i.getCategoryId()).orElse(null);
                if ((i.getKind() == InsightItem.Kind.CHARGE && t == null) || (i.getKind() != InsightItem.Kind.CHARGE && c == null)) {
                    continue;
                }
                open.add(new Item(i.getId(), i.getKind().name(), i.getReason(), i.getTransactionId(),
                        t == null ? null : t.getDay(), t == null ? null : t.getDescription(),
                        t == null ? null : t.getAmountCents(), i.getPattern(), i.getCategoryId(),
                        c == null ? null : c.getName(), i.getLimitCents()));
            }
            out.put("items", open);
            return out;
        });
    }

    /** A month's review, for History. */
    @GetMapping("/month")
    public Map<String, Object> month(@RequestParam String month) {
        Note n = Note.of(notes.findByKindAndPeriod(Insight.Kind.MONTH, month).orElse(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", n);
        return out;
    }

    /** Asks Claude for a fresh take now. Takes a little while: it's a real run. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        insights.refresh();
        return overview();
    }

    public record AskBody(@NotBlank @Size(max = 300) String question) {
    }

    @PostMapping("/afford")
    public Insights.Answer afford(@RequestBody @Valid AskBody body) {
        return insights.afford(body.question().trim());
    }

    /** Done (you applied it) or dismissed (not useful); either way it leaves the list. */
    @PostMapping("/items/{id}/{state}")
    public Map<String, Object> close(@PathVariable Long id, @PathVariable String state) {
        tx.executeWithoutResult(s -> {
            InsightItem i = items.findById(id).orElseThrow();
            i.setState("done".equals(state) ? InsightItem.State.DONE : InsightItem.State.DISMISSED);
            items.save(i);
        });
        return overview();
    }

    public record TokenBody(@NotBlank @Size(max = 500) String token) {
    }

    /** Checks the token with one tiny run before keeping it, encrypted. */
    @PutMapping("/token")
    public Map<String, Object> setToken(@RequestBody @Valid TokenBody body) {
        String token = body.token().trim();
        claude.ask("Answer with ok set to true.", "Are you there?", Map.of("type", "object",
                "properties", Map.of("ok", Map.of("type", "boolean")), "required", List.of("ok"),
                "additionalProperties", false), token);
        claude.setToken(token);
        return overview();
    }

    @DeleteMapping("/token")
    public Map<String, Object> removeToken() {
        claude.setToken(null);
        return overview();
    }

    @ExceptionHandler(ClaudeCode.Problem.class)
    ResponseEntity<Map<String, String>> problem(ClaudeCode.Problem e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
