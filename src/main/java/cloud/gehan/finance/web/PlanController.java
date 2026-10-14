package cloud.gehan.finance.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.GoalType;
import cloud.gehan.finance.model.PayFrequency;
import cloud.gehan.finance.model.Paystub;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.SpendingTarget;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.PaystubRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.SpendingTargetRepository;
import cloud.gehan.finance.service.PaycheckService;
import cloud.gehan.finance.service.ReferenceData;
import cloud.gehan.finance.service.RuleService;
import cloud.gehan.finance.service.TargetService;
import cloud.gehan.finance.web.Views.PaystubView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Spending targets, goals and paychecks: the screens behind Home's cards and Flow's income. */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final TargetService targetService;
    private final PaycheckService paychecks;
    private final SpendingTargetRepository targets;
    private final SavingsGoalRepository goals;
    private final PaystubRepository paystubs;
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final AccountRepository accounts;
    private final RuleService ruleService;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public PlanController(TargetService targetService, PaycheckService paychecks, SpendingTargetRepository targets,
            SavingsGoalRepository goals, PaystubRepository paystubs, CategoryRepository categories,
            CategoryRuleRepository rules, AccountRepository accounts, RuleService ruleService, TransactionTemplate tx,
            AppProperties props) {
        this.targetService = targetService;
        this.paychecks = paychecks;
        this.targets = targets;
        this.goals = goals;
        this.paystubs = paystubs;
        this.categories = categories;
        this.rules = rules;
        this.accounts = accounts;
        this.ruleService = ruleService;
        this.tx = tx;
        this.props = props;
    }

    @GetMapping("/targets")
    public TargetService.Progress progress(@RequestParam(required = false) String month) {
        return targetService.progress(month == null || month.isBlank() ? YearMonth.now(props.zoneId()) : YearMonth.parse(month));
    }

    public record TargetBody(long monthlyLimitCents) {
    }

    /** Sets a category's monthly cap; zero removes it. */
    @PutMapping("/targets/{categoryId}")
    public Map<String, Object> setTarget(@PathVariable Long categoryId, @RequestBody TargetBody body) {
        tx.executeWithoutResult(s -> {
            Category c = categories.findById(categoryId).orElseThrow();
            if (c.getKind() != CategoryKind.EXPENSE) {
                throw new IllegalArgumentException("Spending targets are for spending categories.");
            }
            if (body.monthlyLimitCents() <= 0) {
                targets.deleteByCategory(c);
                return;
            }
            SpendingTarget t = targets.findByCategory(c).orElseGet(() -> new SpendingTarget(c, 0));
            t.setMonthlyLimitCents(body.monthlyLimitCents());
            targets.save(t);
        });
        return Map.of("ok", true);
    }

    // --- Goals -------------------------------------------------------------------

    public record GoalBody(@NotBlank String name, @NotNull GoalType type, Long targetCents, Double targetPercent,
            Long accountId, Long categoryId, LocalDate targetDate) {
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createGoal(@RequestBody @Valid GoalBody body) {
        Long id = tx.execute(s -> goals.save(apply(new SavingsGoal(body.name(), body.type()), body)).getId());
        return Map.of("id", id);
    }

    @PutMapping("/goals/{id}")
    public Map<String, Object> updateGoal(@PathVariable Long id, @RequestBody @Valid GoalBody body) {
        tx.executeWithoutResult(s -> apply(goals.findById(id).orElseThrow(), body));
        return Map.of("id", id);
    }

    @DeleteMapping("/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@PathVariable Long id) {
        tx.executeWithoutResult(s -> goals.deleteById(id));
    }

    private SavingsGoal apply(SavingsGoal g, GoalBody body) {
        g.setName(body.name().trim());
        g.setType(body.type());
        g.setTargetCents(body.targetCents());
        g.setTargetPercent(body.targetPercent());
        g.setAccount(body.accountId() == null ? null : accounts.findById(body.accountId()).orElseThrow());
        g.setCategory(body.categoryId() == null ? null : categories.findById(body.categoryId()).orElseThrow());
        g.setTargetDate(body.targetDate());
        switch (body.type()) {
            case SAVINGS_RATE -> {
                if (body.targetPercent() == null || body.targetPercent() <= 0) {
                    throw new IllegalArgumentException("A savings rate goal needs a percentage.");
                }
            }
            default -> {
                if (body.targetCents() == null || body.targetCents() <= 0) {
                    throw new IllegalArgumentException("This goal needs an amount.");
                }
            }
        }
        return g;
    }

    // --- Paychecks ---------------------------------------------------------------

    public record PaycheckPage(PaycheckService.Overview overview, List<PaystubView> paystubs) {
    }

    @GetMapping("/paychecks")
    public PaycheckPage paychecks() {
        return new PaycheckPage(paychecks.overview(), paystubs.findAll().stream().map(PaystubView::of).toList());
    }

    public record TrackBody(@NotBlank String pattern) {
    }

    /** "That's my paycheck": a rule sending these deposits to Paycheck from now on. */
    @PostMapping("/paychecks/track")
    public Map<String, Object> track(@RequestBody @Valid TrackBody body) {
        int refiled = tx.execute(s -> {
            Category paycheck = categories.findByNameIgnoreCase(ReferenceData.PAYCHECK)
                    .orElseThrow(() -> new IllegalStateException("The Paycheck category was deleted; create it again first."));
            rules.save(new CategoryRule(body.pattern().trim().toUpperCase(Locale.ROOT), paycheck, AmountSign.IN, null,
                    Math.max(ReferenceData.USER_PRIORITY, rules.maxPriority() + 1)));
            rules.flush();
            return ruleService.reapply();
        });
        return Map.of("refiled", refiled);
    }

    public record PaystubBody(@NotBlank String employer, String matchPattern, PayFrequency frequency, long grossCents,
            long federalTaxCents, long stateTaxCents, long ficaCents, long retirementCents, long benefitsCents,
            long otherDeductionsCents, long employerMatchCents, Boolean active) {
    }

    @PostMapping("/paystubs")
    @ResponseStatus(HttpStatus.CREATED)
    public PaystubView createPaystub(@RequestBody @Valid PaystubBody body) {
        return tx.execute(s -> PaystubView.of(paystubs.save(apply(new Paystub(body.employer()), body))));
    }

    @PutMapping("/paystubs/{id}")
    public PaystubView updatePaystub(@PathVariable Long id, @RequestBody @Valid PaystubBody body) {
        return tx.execute(s -> PaystubView.of(apply(paystubs.findById(id).orElseThrow(), body)));
    }

    @DeleteMapping("/paystubs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaystub(@PathVariable Long id) {
        tx.executeWithoutResult(s -> paystubs.deleteById(id));
    }

    private static Paystub apply(Paystub p, PaystubBody b) {
        if (b.grossCents() <= 0) {
            throw new IllegalArgumentException("Gross pay must be more than zero.");
        }
        p.setEmployer(b.employer().trim());
        p.setMatchPattern(b.matchPattern() == null || b.matchPattern().isBlank() ? null : b.matchPattern().trim().toUpperCase(Locale.ROOT));
        p.setFrequency(b.frequency() == null ? PayFrequency.BIWEEKLY : b.frequency());
        p.setGrossCents(b.grossCents());
        p.setFederalTaxCents(b.federalTaxCents());
        p.setStateTaxCents(b.stateTaxCents());
        p.setFicaCents(b.ficaCents());
        p.setRetirementCents(b.retirementCents());
        p.setBenefitsCents(b.benefitsCents());
        p.setOtherDeductionsCents(b.otherDeductionsCents());
        p.setEmployerMatchCents(b.employerMatchCents());
        p.setActive(b.active() == null || b.active());
        if (p.netCents() <= 0) {
            throw new IllegalArgumentException("Deductions add up to more than gross pay.");
        }
        return p;
    }
}
