package cloud.gehan.finance.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.SpendingTarget;
import cloud.gehan.finance.model.CategoryRole;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.SpendingTargetRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.service.ReferenceData;
import cloud.gehan.finance.service.RuleService;
import cloud.gehan.finance.web.Views.CategoryView;
import cloud.gehan.finance.web.Views.RuleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class CategoryController {

    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TransactionRepository txns;
    private final SpendingTargetRepository targets;
    private final SavingsGoalRepository goals;
    private final RuleService ruleService;
    private final TransactionTemplate tx;

    public CategoryController(CategoryRepository categories, CategoryRuleRepository rules, TransactionRepository txns,
            SpendingTargetRepository targets, SavingsGoalRepository goals, RuleService ruleService,
            TransactionTemplate tx) {
        this.categories = categories;
        this.rules = rules;
        this.txns = txns;
        this.targets = targets;
        this.goals = goals;
        this.ruleService = ruleService;
        this.tx = tx;
    }

    @GetMapping("/categories")
    public List<CategoryView> list() {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(CategoryView::of).toList();
    }

    /** @param earnedIncome income kind: counts as income; defaults to yes for a new income category */
    public record CategoryBody(@NotBlank String name, @NotNull CategoryKind kind, String icon, Boolean earnedIncome,
            Boolean fixedCost) {
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView create(@RequestBody @Valid CategoryBody body) {
        return tx.execute(s -> {
            if (categories.findByNameIgnoreCase(body.name().trim()).isPresent()) {
                throw new IllegalArgumentException("There is already a category called " + body.name().trim());
            }
            int order = categories.findAll().stream().mapToInt(Category::getSortOrder).max().orElse(0) + 1;
            Category c = new Category(body.name().trim(), body.kind(), ReferenceData.colorFor(body.kind()),
                    blankToNull(body.icon()), order);
            c.setEarnedIncome(body.kind() == CategoryKind.INCOME && !Boolean.FALSE.equals(body.earnedIncome()));
            c.setFixedCost(body.kind() == CategoryKind.EXPENSE && Boolean.TRUE.equals(body.fixedCost()));
            return CategoryView.of(categories.save(c));
        });
    }

    @PatchMapping("/categories/{id}")
    public CategoryView update(@PathVariable Long id, @RequestBody CategoryBody body) {
        return tx.execute(s -> {
            Category c = categories.findById(id).orElseThrow();
            if (body.name() != null && !body.name().isBlank() && !body.name().trim().equals(c.getName())) {
                categories.findByNameIgnoreCase(body.name().trim()).filter(other -> !other.getId().equals(id))
                        .ifPresent(other -> {
                            throw new IllegalArgumentException("There is already a category called " + other.getName());
                        });
                c.setName(body.name().trim());
            }
            if (body.kind() != null && body.kind() != c.getKind()) {
                // Transfers filed as spending would count twice; a job stays with its kind.
                if (!c.getRoles().isEmpty()) {
                    throw new IllegalArgumentException("The app puts " + jobs(c) + " in " + c.getName()
                            + ", so it stays the kind it is. To change that, delete it and move them to another category.");
                }
                c.setKind(body.kind());
                c.setColor(ReferenceData.colorFor(body.kind()));
                // Only income can be earned and only spending can be a monthly bill.
                c.setEarnedIncome(body.kind() == CategoryKind.INCOME && !Boolean.FALSE.equals(body.earnedIncome()));
                c.setFixedCost(body.kind() == CategoryKind.EXPENSE && Boolean.TRUE.equals(body.fixedCost()));
            }
            if (body.icon() != null) {
                c.setIcon(blankToNull(body.icon()));
            }
            if (body.earnedIncome() != null) {
                c.setEarnedIncome(c.getKind() == CategoryKind.INCOME && body.earnedIncome());
            }
            if (body.fixedCost() != null) {
                c.setFixedCost(c.getKind() == CategoryKind.EXPENSE && body.fixedCost());
            }
            return CategoryView.of(c);
        });
    }

    /**
     * Deletes a category. With {@code moveTo}, everything in it (transactions, rules, its
     * target, goals and any job the app gives it) moves to that category. Without, its
     * transactions go back to the rules and show up in To review again, and its rules and
     * target go with it. A category the app files things into needs somewhere to move them.
     */
    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @RequestParam(required = false) Long moveTo) {
        tx.executeWithoutResult(s -> {
            Category c = categories.findById(id).orElseThrow();
            Category to = moveTo == null ? null : categories.findById(moveTo).orElseThrow();
            if (to != null && to.getId().equals(c.getId())) {
                throw new IllegalArgumentException("Pick a different category to move them to.");
            }
            boolean needed = c.getRoles().stream().anyMatch(r -> !r.optional());
            if (needed && to == null) {
                throw new IllegalArgumentException("The app puts " + jobs(c) + " in " + c.getName()
                        + ". Pick the category to put them in instead.");
            }
            if (to != null && needed && to.getKind() != c.getKind()) {
                throw new IllegalArgumentException("The app puts " + jobs(c) + " in " + c.getName()
                        + ", so they need another " + KIND_WORDS.get(c.getKind()) + " category.");
            }
            // Leftovers can't share a category you use for something: every Shopping purchase
            // would then look unsorted. They go to a new, empty one (or rename this one instead).
            if (to != null && c.getRoles().stream().anyMatch(CategoryRole::catchAll) && inUse(to)) {
                throw new IllegalArgumentException("The app puts " + jobs(c) + " in " + c.getName()
                        + ". They need a category of their own: make a new, empty one to move them to, or just rename "
                        + c.getName() + ".");
            }
            for (Transaction t : txns.findByCategory(c)) {
                if (to != null) {
                    t.setCategory(to);
                } else {
                    // Back to the rules, and back on your list to check.
                    t.setCategory(null);
                    t.setCategorySource(CategorySource.DEFAULT);
                    t.setReviewed(false);
                }
            }
            // A goal counts saving into a category; anything else, and it counts all saving.
            for (SavingsGoal g : goals.findByCategory(c)) {
                g.setCategory(to != null && to.getKind() == CategoryKind.SAVINGS ? to : null);
            }
            if (to != null) {
                for (CategoryRule r : rules.findAll()) {
                    if (r.getCategory().getId().equals(c.getId())) {
                        r.setCategory(to);
                    }
                }
                // Its spending target carries over to spending without one of its own.
                targets.findByCategory(c).ifPresent(t -> {
                    if (to.getKind() == CategoryKind.EXPENSE && targets.findByCategory(to).isEmpty()) {
                        targets.save(new SpendingTarget(to, t.getMonthlyLimitCents()));
                    }
                });
                // A job only fits its kind; an optional one that doesn't fit just goes.
                to.addRoles(c.getRoles().stream().filter(r -> r.kind() == to.getKind()).toList());
                // Pay still counts as income, and loans are still a monthly bill.
                if (to.hasRole(CategoryRole.PAYCHECK)) {
                    to.setEarnedIncome(true);
                }
                if (to.hasRole(CategoryRole.LOANS)) {
                    to.setFixedCost(true);
                }
            } else {
                rules.deleteByCategory(c);
            }
            // Through the entity, not a bulk delete: it may already be loaded above.
            targets.findByCategory(c).ifPresent(targets::delete);
            targets.flush();
            categories.delete(c);
            categories.flush();
            ruleService.reapply();
        });
    }

    static final Map<CategoryKind, String> KIND_WORDS = Map.of(CategoryKind.EXPENSE, "spending", CategoryKind.INCOME,
            "income", CategoryKind.SAVINGS, "saving & investing", CategoryKind.TRANSFER, "not counted");

    /** Has rules or transactions of its own. */
    private boolean inUse(Category c) {
        return rules.findAll().stream().anyMatch(r -> r.getCategory().getId().equals(c.getId()))
                || !txns.findByCategory(c).isEmpty();
    }

    private static String jobs(Category c) {
        List<String> jobs = c.getRoles().stream().map(CategoryRole::job).toList();
        return jobs.size() == 1 ? jobs.get(0)
                : String.join(", ", jobs.subList(0, jobs.size() - 1)) + " and " + jobs.get(jobs.size() - 1);
    }

    // --- Rules -------------------------------------------------------------------

    @GetMapping("/rules")
    public List<RuleView> rules() {
        return rules.findAllByOrderByPriorityDescIdAsc().stream().map(RuleView::of).toList();
    }

    public record RuleBody(@NotBlank String pattern, @NotNull Long categoryId, AmountSign sign, AccountType accountType) {
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRule(@RequestBody @Valid RuleBody body) {
        return tx.execute(s -> {
            Category c = categories.findById(body.categoryId()).orElseThrow();
            int priority = Math.max(ReferenceData.USER_PRIORITY, rules.maxPriority() + 1);
            CategoryRule rule = rules.save(new CategoryRule(body.pattern().trim().toUpperCase(Locale.ROOT), c,
                    body.sign() == null ? AmountSign.ANY : body.sign(), body.accountType(), priority));
            rules.flush();
            return Map.of("rule", RuleView.of(rule), "refiled", ruleService.reapply());
        });
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        return tx.execute(s -> {
            rules.deleteById(id);
            rules.flush();
            return Map.of("refiled", ruleService.reapply());
        });
    }

    @PostMapping("/rules/reapply")
    public Map<String, Object> reapply() {
        return Map.of("refiled", tx.execute(s -> ruleService.reapply()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
