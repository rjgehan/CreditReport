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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
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
            if (body.name() != null && !body.name().isBlank()) {
                c.setName(body.name().trim());
            }
            if (body.kind() != null) {
                c.setKind(body.kind());
                c.setColor(ReferenceData.colorFor(body.kind()));
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

    /** Its transactions go back to the rules; its rules and target go with it. */
    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tx.executeWithoutResult(s -> {
            Category c = categories.findById(id).orElseThrow();
            for (Transaction t : txns.findByCategory(c)) {
                t.setCategory(null);
                t.setCategorySource(CategorySource.DEFAULT);
            }
            for (SavingsGoal g : goals.findByCategory(c)) {
                g.setCategory(null);
            }
            rules.deleteByCategory(c);
            targets.deleteByCategory(c);
            categories.delete(c);
            categories.flush();
            ruleService.reapply();
        });
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
