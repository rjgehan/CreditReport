package cloud.gehan.finance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * The server half of the iPhone shortcut that files leftovers with Apple
 * Intelligence. The phone asks for a prompt, runs it through Apple's model, and
 * posts the model's reply back; everything clever happens here, so the shortcut
 * stays four plain actions.
 *
 * <p>Only transactions nothing else could file are sent (no rule, no provider
 * category, no transfer match), and the reply is checked line by line: unknown
 * ids, unknown categories and anything already filed are ignored.
 */
@Service
public class ShortcutCategorizer {

    /** Apple's on-device model has a small context window; this many lines fit comfortably. */
    static final int BATCH = 40;
    static final int LOOKBACK_DAYS = 120;
    /** Where an unfiled transaction lands today; these are what the shortcut is for. */
    static final Set<String> LEFTOVERS = Set.of(ReferenceData.UNCATEGORIZED, ReferenceData.OTHER_INCOME, ReferenceData.REFUNDS);
    // The id is the number right before the separator, so "1. 123 | Dining" and
    // "**123** | Dining" both read as 123; at most 18 digits, so it always fits a long.
    private static final Pattern LINE = Pattern.compile("(\\d{1,18})\\W*?\\s*[|:–—]\\s*\\**(.+?)\\**\\s*$");

    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final Settings settings;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final SecureRandom random = new SecureRandom();

    public ShortcutCategorizer(TransactionRepository txns, CategoryRepository categories, Settings settings,
            TransactionTemplate tx, AppProperties props) {
        this.txns = txns;
        this.categories = categories;
        this.settings = settings;
        this.tx = tx;
        this.zone = props.zoneId();
    }

    // --- The token the shortcut sends ---------------------------------------------------

    /** Makes a new token (replacing any old one) and returns it; only its hash is kept. */
    public String newToken() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        settings.setShortcutTokenHash(hash(token));
        return token;
    }

    public boolean configured() {
        return settings.shortcutTokenHash() != null;
    }

    /** Accepts "Bearer xyz" or a bare token. Constant-time against the stored hash. */
    public boolean authorized(String header) {
        String stored = settings.shortcutTokenHash();
        if (stored == null || header == null) {
            return false;
        }
        String token = header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                hash(token).getBytes(StandardCharsets.UTF_8));
    }

    // --- The conversation --------------------------------------------------------------

    public String prompt() {
        List<Transaction> todo = leftovers();
        List<Category> choices = choices();
        StringBuilder sb = new StringBuilder();
        sb.append("You file bank transactions into budget categories.\n\n");
        if (todo.isEmpty()) {
            return sb.append("There are no transactions to file today. Reply with exactly: none\n").toString();
        }
        sb.append("Categories (use these names exactly):\n");
        for (Category c : choices) {
            sb.append("- ").append(c.getName()).append('\n');
        }
        sb.append("\nFor each transaction, reply with one line: its number, a vertical bar, then the category.\n")
                .append("Example: 123 | Groceries\n")
                .append("Negative amounts are money spent; positive amounts are money received.\n")
                .append("If you are not sure, use: 123 | skip\n")
                .append("Reply with those lines only, nothing else.\n\n")
                .append("Transactions (number | date | amount | description):\n");
        for (Transaction t : todo) {
            sb.append(t.getId()).append(" | ").append(t.getDay()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", t.getAmountCents() / 100.0)).append(" | ")
                    .append(t.matchText().replaceAll("[\\r\\n|]", " ")).append('\n');
        }
        return sb.toString();
    }

    /** Applies the model's reply. Returns a one-line result for the shortcut's notification. */
    public String answer(String reply) {
        if (reply == null || reply.isBlank()) {
            return "The model didn't reply.";
        }
        Map<String, Category> byName = new HashMap<>();
        for (Category c : choices()) {
            byName.put(normalise(c.getName()), c);
        }
        Map<Long, Transaction> open = new HashMap<>();
        for (Transaction t : leftovers()) {
            open.put(t.getId(), t);
        }
        if (open.isEmpty()) {
            return "Nothing to file.";
        }
        Map<Long, Category> picks = new HashMap<>();
        for (String line : reply.split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            long id = Long.parseLong(m.group(1));
            Category c = byName.get(normalise(m.group(2)));
            Transaction t = open.get(id);
            // Money out can only be spending; money in can be income or a refund of spending.
            if (c != null && t != null && (t.getAmountCents() > 0 || c.getKind() == CategoryKind.EXPENSE)) {
                picks.put(id, c);
            }
        }
        int filed = tx.execute(s -> {
            int n = 0;
            for (Map.Entry<Long, Category> e : picks.entrySet()) {
                Transaction t = txns.findById(e.getKey()).orElse(null);
                // Re-checked inside the write: a sync or a hand edit may have filed it since.
                if (t != null && t.getCategorySource() == CategorySource.DEFAULT) {
                    t.setCategory(e.getValue());
                    t.setCategorySource(CategorySource.AI);
                    n++;
                }
            }
            return n;
        });
        int left = open.size() - filed;
        return filed == 0 ? "Nothing filed; " + left + " still need a category."
                : "Filed " + filed + (filed == 1 ? " transaction" : " transactions")
                        + (left > 0 ? "; " + left + " left for you." : ".");
    }

    /** How many transactions the next run would send (up to one batch). */
    public int waiting() {
        return leftovers().size();
    }

    List<Transaction> leftovers() {
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        // Give a transfer's other half time to post before guessing: once paired it isn't a leftover.
        LocalDate settled = today.minusDays(TransferMatcher.MAX_DAYS_APART + 1L);
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : txns.findByDayGreaterThanEqual(from)) {
            if (t.getDay().isAfter(settled)) {
                continue;
            }
            boolean guess = t.getCategorySource() == null || t.getCategorySource() == CategorySource.DEFAULT;
            boolean leftover = t.getCategory() == null || LEFTOVERS.contains(t.getCategory().getName());
            if (guess && leftover && !t.isPending() && !t.getAccount().isHidden() && t.getPairedTxnId() == null) {
                out.add(t);
            }
        }
        out.sort((a, b) -> b.getDay().compareTo(a.getDay()));
        return out.size() > BATCH ? out.subList(0, BATCH) : out;
    }

    /** What the model may choose: spending and income categories, minus the catch-alls. */
    List<Category> choices() {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> c.getKind() == CategoryKind.EXPENSE || c.getKind() == CategoryKind.INCOME)
                .filter(c -> !ReferenceData.UNCATEGORIZED.equals(c.getName()))
                .toList();
    }

    /** Lower case, letters and digits only, so "Dining." or "- dining" still match "Dining". */
    static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
