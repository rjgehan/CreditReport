package cloud.gehan.finance.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;

/**
 * Picks a category for every transaction you haven't picked one for yourself.
 * Rules match their words wherever they appear, whatever punctuation or numbers
 * sit between them (see {@link WordMatch}).
 *
 * <p>In order: your rules (highest priority first, then the longest pattern, so
 * UBER EATS beats UBER), the provider's own category, an existing transfer pairing,
 * and finally a guess from the account type and the direction of the money.
 */
@Component
public class Categorizer {

    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;

    public Categorizer(CategoryRepository categories, CategoryRuleRepository rules) {
        this.categories = categories;
        this.rules = rules;
    }

    public void categorize(Collection<Transaction> txns) {
        if (txns.isEmpty()) {
            return;
        }
        Context ctx = context();
        for (Transaction t : txns) {
            apply(t, ctx);
        }
    }

    /** Compiled once per batch: rules are few, transactions are many. */
    public Context context() {
        List<Compiled> compiled = rules.findAllByOrderByPriorityDescIdAsc().stream()
                .sorted(Comparator.comparingInt((CategoryRule r) -> r.getPriority()).reversed()
                        .thenComparing(r -> -r.getPattern().length()))
                .map(r -> new Compiled(r, WordMatch.of(r.getPattern())))
                .toList();
        Map<String, Category> byName = new HashMap<>();
        for (Category c : categories.findAll()) {
            byName.put(c.getName().toLowerCase(Locale.ROOT), c);
        }
        return new Context(compiled, byName);
    }

    public void apply(Transaction t, Context ctx) {
        if (t.getCategorySource() == CategorySource.MANUAL && t.getCategory() != null) {
            return;
        }
        AccountType accountType = t.getAccount().getType();
        CategoryRule r = firstRule(t, ctx);
        // Two sides of a move between your own accounts stay a transfer whatever a built-in
        // rule says (a Zelle to yourself isn't paying someone); only a rule of yours re-files it.
        boolean paired = t.getCategorySource() == CategorySource.TRANSFER_MATCH && t.getPairedTxnId() != null
                && t.getCategory() != null;
        if (r != null && !(paired && r.getPriority() < ReferenceData.USER_PRIORITY)) {
            set(t, guardSavings(t, r.getCategory(), ctx), CategorySource.RULE);
            return;
        }
        // The shortcut only files what nothing else could, so its choice stands until a rule or you say otherwise.
        if (t.getCategorySource() == CategorySource.AI && t.getCategory() != null) {
            return;
        }
        if (t.getCategorySource() == CategorySource.TRANSFER_MATCH && t.getPairedTxnId() != null && t.getCategory() != null) {
            return;
        }
        set(t, ctx.named(defaultFor(accountType, t.getAmountCents())), CategorySource.DEFAULT);
    }

    /** The rule that files this transaction, or null when none matches. */
    public CategoryRule firstRule(Transaction t, Context ctx) {
        WordMatch.Text text = WordMatch.Text.of(t.matchText());
        AccountType accountType = t.getAccount().getType();
        for (Compiled c : ctx.rules) {
            CategoryRule r = c.rule;
            if (r.getSign().matches(t.getAmountCents())
                    && (r.getAccountType() == null || r.getAccountType() == accountType)
                    // An employer's name on a card refund or a 401(k) contribution isn't pay,
                    // and neither is a small reimbursement that comes through like a paycheck.
                    && (accountType.receivesPay() || !r.getCategory().isEarnedIncome())
                    && (t.getAmountCents() >= PaycheckService.MIN_CENTS
                            || !ReferenceData.PAYCHECK.equals(r.getCategory().getName()))
                    && c.words.in(text)) {
                return r;
            }
        }
        return null;
    }

    static String defaultFor(AccountType type, long amountCents) {
        boolean in = amountCents > 0;
        return switch (type) {
            // Money moving inside savings and investment accounts is buying, selling
            // and transfers; what counts as saving is the move in from checking.
            case SAVINGS, BROKERAGE, RETIREMENT, LOAN -> ReferenceData.TRANSFER;
            case CREDIT_CARD -> in ? ReferenceData.REFUNDS : ReferenceData.UNCATEGORIZED;
            default -> in ? ReferenceData.OTHER_INCOME : ReferenceData.UNCATEGORIZED;
        };
    }

    /** Files it; a different category from the one you checked off puts it back on your list. */
    static void set(Transaction t, Category category, CategorySource source) {
        Long before = t.getCategory() == null ? null : t.getCategory().getId();
        Long after = category == null ? null : category.getId();
        if (t.isReviewed() && before != null && !java.util.Objects.equals(before, after)) {
            t.setReviewed(false);
        }
        t.setCategory(category);
        t.setCategorySource(source);
    }

    /**
     * Inside a savings, brokerage or retirement account, money arriving is the far
     * side of a deposit already counted as saved where it left checking. A WEBULL
     * rule must not count it a second time.
     */
    private Category guardSavings(Transaction t, Category chosen, Context ctx) {
        if (chosen != null && chosen.getKind() == CategoryKind.SAVINGS
                && t.getAccount().getType().savingsLike()) {
            Category transfer = ctx.named(ReferenceData.TRANSFER);
            return transfer != null ? transfer : chosen;
        }
        return chosen;
    }

    /**
     * Whole-word, case-insensitive. Word edges only apply where the pattern itself
     * starts or ends with a letter or digit, so "SQ *" still matches "SQ *JOES".
     */
    static Pattern wordPattern(String raw) {
        String p = raw.trim().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        if (!p.isEmpty() && Character.isLetterOrDigit(p.charAt(0))) {
            sb.append("(?<![A-Z0-9])");
        }
        sb.append(Pattern.quote(p));
        if (!p.isEmpty() && Character.isLetterOrDigit(p.charAt(p.length() - 1))) {
            sb.append("(?![A-Z0-9])");
        }
        return Pattern.compile(sb.toString());
    }

    record Compiled(CategoryRule rule, WordMatch words) {
    }

    public record Context(List<Compiled> rules, Map<String, Category> byName) {
        Category named(String name) {
            return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
