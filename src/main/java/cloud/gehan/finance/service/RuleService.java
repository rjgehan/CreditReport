package cloud.gehan.finance.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Service;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.TransactionRepository;

@Service
public class RuleService {

    private final TransactionRepository txns;
    private final Categorizer categorizer;
    private final TransferMatcher matcher;

    public RuleService(TransactionRepository txns, Categorizer categorizer, TransferMatcher matcher) {
        this.txns = txns;
        this.categorizer = categorizer;
        this.matcher = matcher;
    }

    /**
     * Runs every rule again over everything you haven't categorised by hand.
     * Call inside a transaction.
     *
     * @return how many transactions changed category
     */
    public int reapply() {
        List<Transaction> all = txns.findByCategorySourceNot(CategorySource.MANUAL);
        Categorizer.Context ctx = categorizer.context();
        int changed = 0;
        for (Transaction t : all) {
            Category before = t.getCategory();
            categorizer.apply(t, ctx);
            if (!Objects.equals(id(before), id(t.getCategory()))) {
                changed++;
            }
        }
        matcher.match(txns.findAll(), ctx);
        return changed;
    }

    private static Long id(Category c) {
        return c == null ? null : c.getId();
    }

    /**
     * The words that identify where a transaction came from, without the store
     * number, dates and reference codes: "STARBUCKS STORE 22451" becomes
     * "STARBUCKS STORE", or the merchant name when the provider gave one.
     */
    public static String suggestPattern(Transaction t) {
        // A payment to a person: the rule is about that person, not every Venmo or Zelle.
        boolean person = ReferenceData.PEOPLE_WORDS.stream().anyMatch(w -> WordMatch.of(w).in(t.matchText()));
        if (!person && t.getMerchant() != null && !t.getMerchant().isBlank()) {
            return t.getMerchant().trim().toUpperCase(Locale.ROOT);
        }
        String text = t.getDescription().toUpperCase(Locale.ROOT)
                .replaceAll("\\b(PPD|CCD|WEB|ID|DES|INDN|POS|PURCHASE|DEBIT|CARD|ACH)\\b", " ")
                .replaceAll("[#*]?\\S*\\d\\S*", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] words = text.split(" ");
        String pattern = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, person ? 5 : 3))).trim();
        return pattern.isEmpty() ? t.getDescription().trim().toUpperCase(Locale.ROOT) : pattern;
    }
}
