package cloud.gehan.finance.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.Categorizer;
import cloud.gehan.finance.service.ReferenceData;
import cloud.gehan.finance.service.RuleService;
import cloud.gehan.finance.service.StatementImporter;
import cloud.gehan.finance.service.SyncService;
import cloud.gehan.finance.web.Views.TxnView;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository txns;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final Categorizer categorizer;
    private final RuleService ruleService;
    private final StatementImporter importer;
    private final SyncService sync;
    private final TransactionTemplate tx;

    public TransactionController(TransactionRepository txns, AccountRepository accounts, CategoryRepository categories,
            CategoryRuleRepository rules, Categorizer categorizer, RuleService ruleService, StatementImporter importer,
            SyncService sync, TransactionTemplate tx) {
        this.txns = txns;
        this.accounts = accounts;
        this.categories = categories;
        this.rules = rules;
        this.categorizer = categorizer;
        this.ruleService = ruleService;
        this.importer = importer;
        this.sync = sync;
        this.tx = tx;
    }

    /** @param toReview how many of the transactions these filters find you haven't checked off yet */
    public record TxnPage(List<TxnView> items, long total, int page, int pages, long inCents, long outCents,
            long toReview) {
    }

    /** Not checked off yet. */
    static final Specification<Transaction> UNREVIEWED = (root, query, cb) -> cb.or(cb.isNull(root.get("reviewed")),
            cb.isFalse(root.get("reviewed")));

    /**
     * @param category a category id, or "none" for transactions without one (or in Uncategorized)
     * @param kind INCOME, EXPENSE, SAVINGS or TRANSFER
     * @param review "todo" for only the ones you haven't checked off
     */
    @GetMapping("/transactions")
    public TxnPage list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long account, @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String q,
            @RequestParam(required = false) String review,
            @RequestParam(required = false) LocalDate beforeDay, @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Specification<Transaction> filters = filters(from, to, account, category, kind, q);
        Specification<Transaction> spec = "todo".equals(review) ? filters.and(UNREVIEWED) : filters;
        int pageSize = Math.max(1, Math.min(size, 200));
        // "More" continues after the last row shown rather than at a page number, so rows
        // checked off meanwhile (and gone from To review) don't make it skip any.
        Specification<Transaction> after = beforeDay == null || beforeId == null ? spec
                : spec.and((root, query, cb) -> cb.or(cb.lessThan(root.get("day"), beforeDay),
                        cb.and(cb.equal(root.get("day"), beforeDay), cb.lessThan(root.get("id"), beforeId))));
        Page<Transaction> result = txns.findAll(after,
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by(Sort.Order.desc("day"), Sort.Order.desc("id"))));
        long in = 0;
        long out = 0;
        long total = 0;
        for (Transaction t : txns.findAll(spec)) {
            total++;
            if (t.getAmountCents() > 0) {
                in += t.getAmountCents();
            } else {
                out -= t.getAmountCents();
            }
        }
        return new TxnPage(result.getContent().stream().map(TxnView::of).toList(), total,
                result.getNumber(), result.getTotalPages(), in, out, txns.count(filters.and(UNREVIEWED)));
    }

    public record ReviewBody(@NotNull Boolean reviewed) {
    }

    /** Checks off (or un-checks) every transaction the same filters find. */
    @PostMapping("/transactions/review")
    public Map<String, Object> reviewAll(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long account,
            @RequestParam(required = false) String category, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q, @RequestBody @Valid ReviewBody body) {
        int changed = tx.execute(s -> {
            int n = 0;
            for (Transaction t : txns.findAll(filters(from, to, account, category, kind, q))) {
                if (t.isReviewed() != body.reviewed()) {
                    t.setReviewed(body.reviewed());
                    n++;
                }
            }
            return n;
        });
        return Map.of("changed", changed);
    }

    /** Why a transaction is in its category, in words. */
    public record Reason(String source, String text, Long ruleId, String rulePattern, boolean ruleBuiltIn) {
    }

    @GetMapping("/transactions/{id}/reason")
    public Reason reason(@PathVariable Long id) {
        return tx.execute(s -> {
            Transaction t = txns.findById(id).orElseThrow();
            CategorySource source = t.getCategorySource() == null ? CategorySource.DEFAULT : t.getCategorySource();
            return switch (source) {
                case MANUAL -> new Reason(source.name(), "You picked it", null, null, false);
                case AI -> new Reason(source.name(), "Guessed by the iPhone shortcut", null, null, false);
                case TRANSFER_MATCH -> new Reason(source.name(), "Matched with the other side of a transfer", null, null, false);
                case RULE -> {
                    CategoryRule r = categorizer.firstRule(t, categorizer.context());
                    yield r == null ? new Reason(source.name(), "A rule", null, null, false)
                            : new Reason(source.name(), (r.getPriority() < ReferenceData.USER_PRIORITY ? "Built-in rule: " : "Your rule: ")
                                    + r.getPattern(), r.getId(), r.getPattern(), r.getPriority() < ReferenceData.USER_PRIORITY);
                }
                default -> new Reason(source.name(), "Nothing matched, so the account's default", null, null, false);
            };
        });
    }

    private static Specification<Transaction> filters(LocalDate from, LocalDate to, Long account, String category,
            String kind, String q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("day"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("day"), to));
            }
            if (account != null) {
                where.add(cb.equal(root.get("account").get("id"), account));
            } else {
                where.add(cb.isFalse(root.get("account").get("hidden")));
            }
            if ("none".equals(category)) {
                Join<Transaction, Category> c = root.join("category", JoinType.LEFT);
                where.add(cb.or(cb.isNull(root.get("category")), cb.equal(c.get("name"), ReferenceData.UNCATEGORIZED)));
            } else if (category != null && !category.isBlank()) {
                where.add(cb.equal(root.get("category").get("id"), Long.valueOf(category)));
            }
            if (kind != null && !kind.isBlank()) {
                where.add(cb.equal(root.get("category").get("kind"), CategoryKind.valueOf(kind.toUpperCase(Locale.ROOT))));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("merchant"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), like)));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    /**
     * @param ruleFromThis also make a rule from this transaction's merchant, and
     *        re-file every matching transaction you haven't picked by hand
     */
    public record TxnPatch(Long categoryId, Boolean clearCategory, String notes, Boolean ruleFromThis, String rulePattern,
            LocalDate day, Long amountCents, String description, Boolean reviewed) {
    }

    @PatchMapping("/transactions/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TxnPatch body) {
        int refiled = tx.execute(s -> {
            Transaction t = txns.findById(id).orElseThrow();
            if (body.notes() != null) {
                t.setNotes(body.notes().isBlank() ? null : body.notes().trim());
            }
            if (body.reviewed() != null) {
                t.setReviewed(body.reviewed());
            }
            // What you typed in yourself can be corrected; what a bank sent can't.
            if (t.isEditable()) {
                if (body.day() != null) {
                    t.setDay(body.day());
                }
                if (body.amountCents() != null) {
                    t.setAmountCents(body.amountCents());
                }
                if (body.description() != null && !body.description().isBlank()) {
                    t.setDescription(body.description().trim());
                }
            }
            if (Boolean.TRUE.equals(body.clearCategory())) {
                // Hand it back to the rules.
                t.setCategorySource(CategorySource.DEFAULT);
                t.setReviewed(false);
                categorizer.categorize(List.of(t));
            } else if (body.categoryId() != null) {
                Category c = categories.findById(body.categoryId()).orElseThrow();
                t.setCategory(c);
                t.setCategorySource(CategorySource.MANUAL);
                // Picking a category is checking it off.
                t.setReviewed(true);
                if (Boolean.TRUE.equals(body.ruleFromThis())) {
                    String pattern = body.rulePattern() != null && !body.rulePattern().isBlank()
                            ? body.rulePattern() : RuleService.suggestPattern(t);
                    rules.save(new CategoryRule(pattern.trim().toUpperCase(Locale.ROOT), c,
                            t.getAmountCents() < 0 ? AmountSign.OUT : AmountSign.IN, null, rules.maxPriority() < ReferenceData.USER_PRIORITY
                                    ? ReferenceData.USER_PRIORITY : rules.maxPriority() + 1));
                    return ruleService.reapply();
                }
            }
            return 0;
        });
        return Map.of("transaction", TxnView.of(txns.findById(id).orElseThrow()), "refiled", refiled);
    }

    public record NewTxn(@NotNull Long accountId, @NotNull LocalDate day, long amountCents, @NotBlank String description,
            Long categoryId, String notes) {
    }

    /** By hand, for a manual account: a cash purchase, a Webull dividend you want on record. */
    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TxnView create(@RequestBody @Valid NewTxn body) {
        return tx.execute(s -> {
            Account a = accounts.findById(body.accountId()).orElseThrow();
            Transaction t = new Transaction(a, Transaction.BY_HAND + System.nanoTime(), body.day(), body.amountCents(),
                    body.description().trim());
            t.setReviewed(true);
            t.setNotes(body.notes());
            if (body.categoryId() != null) {
                t.setCategory(categories.findById(body.categoryId()).orElseThrow());
                t.setCategorySource(CategorySource.MANUAL);
            } else {
                categorizer.categorize(List.of(t));
            }
            return TxnView.of(txns.save(t));
        });
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tx.executeWithoutResult(s -> {
            Transaction t = txns.findById(id).orElseThrow();
            if (!t.isEditable()) {
                throw new IllegalStateException("Synced transactions come back on the next sync; recategorize it as a Transfer instead.");
            }
            sync.delete(List.of(t));
        });
    }

    @PostMapping("/import")
    public StatementImporter.Result importFile(@RequestParam Long accountId, @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "false") boolean flipSign) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty.");
        }
        return importer.importFile(accountId, file.getInputStream(), flipSign);
    }
}
