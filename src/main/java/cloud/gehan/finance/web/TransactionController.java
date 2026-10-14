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

    public record TxnPage(List<TxnView> items, long total, int page, int pages, long inCents, long outCents) {
    }

    /**
     * @param category a category id, or "none" for transactions without one
     * @param kind INCOME, EXPENSE, SAVINGS or TRANSFER
     */
    @GetMapping("/transactions")
    public TxnPage list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long account, @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Specification<Transaction> spec = (root, query, cb) -> {
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
                where.add(cb.isNull(root.get("category")));
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
        int pageSize = Math.max(1, Math.min(size, 200));
        Page<Transaction> result = txns.findAll(spec,
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by(Sort.Order.desc("day"), Sort.Order.desc("id"))));
        long in = 0;
        long out = 0;
        for (Transaction t : txns.findAll(spec)) {
            if (t.getAmountCents() > 0) {
                in += t.getAmountCents();
            } else {
                out -= t.getAmountCents();
            }
        }
        return new TxnPage(result.getContent().stream().map(TxnView::of).toList(), result.getTotalElements(),
                result.getNumber(), result.getTotalPages(), in, out);
    }

    /**
     * @param ruleFromThis also make a rule from this transaction's merchant, and
     *        re-file every matching transaction you haven't picked by hand
     */
    public record TxnPatch(Long categoryId, Boolean clearCategory, String notes, Boolean ruleFromThis, String rulePattern,
            LocalDate day, Long amountCents, String description) {
    }

    @PatchMapping("/transactions/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TxnPatch body) {
        int refiled = tx.execute(s -> {
            Transaction t = txns.findById(id).orElseThrow();
            if (body.notes() != null) {
                t.setNotes(body.notes().isBlank() ? null : body.notes().trim());
            }
            if (t.getAccount().isManual()) {
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
                categorizer.categorize(List.of(t));
            } else if (body.categoryId() != null) {
                Category c = categories.findById(body.categoryId()).orElseThrow();
                t.setCategory(c);
                t.setCategorySource(CategorySource.MANUAL);
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
            Transaction t = new Transaction(a, "manual:" + System.nanoTime(), body.day(), body.amountCents(),
                    body.description().trim());
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
            if (!t.getAccount().isManual()) {
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
