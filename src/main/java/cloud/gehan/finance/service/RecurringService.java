package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Cadence;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.RecurringCharge;
import cloud.gehan.finance.model.RecurringKind;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.RecurringChargeRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Monthly expenses: the charges that come round on their own. Found in the bank
 * history (the same payee, about the same amount, at a steady rhythm), plus the
 * ones you add by hand, like a yearly subscription the history is too short to show.
 */
@Service
@Transactional(readOnly = true)
public class RecurringService {

    static final int LOOKBACK_DAYS = 400;
    /** Bills whose amount wanders from one time to the next (a power bill), so they're matched loosely. */
    static final Set<String> VARIABLE = Set.of("Utilities", "Insurance");
    /** Everyday spending: somewhere you go every week isn't a weekly subscription. */
    static final Set<String> EVERYDAY = Set.of("Dining", "Groceries", "Transportation", "Gas", "Personal Care",
            "Shopping", "Entertainment");
    /** Money moved to an account that isn't linked: not an expense, whatever its category. */
    static final Pattern TRANSFER = Pattern.compile("\\b(TRANSFER|XFER|TO SAVINGS|TO CHECKING)\\b");

    private final TransactionRepository txns;
    private final RecurringChargeRepository charges;
    private final Settings settings;
    private final ZoneId zone;

    public RecurringService(TransactionRepository txns, RecurringChargeRepository charges, Settings settings,
            AppProperties props) {
        this.txns = txns;
        this.charges = charges;
        this.settings = settings;
        this.zone = props.zoneId();
    }

    /**
     * @param key what "Not recurring" remembers it by; null for one you added
     * @param chargeId the hand-added charge, or null for one found in the history
     * @param amountCents each charge (the latest one)
     * @param monthlyCents what it comes to a month
     * @param nextDate when it's next due; today when it's due and hasn't come through yet
     * @param search what to look for in Activity to see its charges
     */
    public record Item(String key, Long chargeId, String name, RecurringKind kind, String cadence, long amountCents,
            long monthlyCents, LocalDate lastDate, LocalDate nextDate, String categoryName, String accountName,
            String logo, String search, int count) {
    }

    /**
     * @param stopped found before but not seen lately: likely cancelled
     * @param hidden how many you've said aren't recurring
     */
    public record Overview(long monthlyCents, List<Item> items, List<Item> stopped, int hidden) {
    }

    public Overview overview() {
        LocalDate today = LocalDate.now(zone);
        Set<String> hidden = settings.hiddenRecurring();
        List<RecurringCharge> mine = charges.findAll();
        List<Item> active = new ArrayList<>();
        List<Item> stopped = new ArrayList<>();
        for (Found f : find(txns.findByDayGreaterThanEqual(today.minusDays(LOOKBACK_DAYS)), today)) {
            if (hidden.contains(f.item().key()) || mine.stream().anyMatch(c -> same(c, f.item()))) {
                continue;
            }
            (f.active() ? active : stopped).add(f.item());
        }
        for (RecurringCharge c : mine) {
            active.add(new Item(null, c.getId(), c.getName(), c.getKind(), c.getCadence().name(), c.getAmountCents(),
                    c.getCadence().monthly(c.getAmountCents()), null, nextOnOrAfter(c.getNextDate(), c.getCadence(), today),
                    null, null, MerchantLogos.domainFor(c.getName()), c.getName(), 0));
        }
        active.sort(Comparator.comparingLong(Item::monthlyCents).reversed());
        stopped.sort(Comparator.comparing(Item::lastDate).reversed());
        long monthly = active.stream().mapToLong(Item::monthlyCents).sum();
        return new Overview(monthly, active, stopped, hidden.size());
    }

    /** One you added by hand that the history has since found too: it's counted once. */
    static boolean same(RecurringCharge c, Item found) {
        if (!c.getCadence().name().equals(found.cadence())
                || Math.abs(c.getAmountCents() - found.amountCents()) > Math.max(100, c.getAmountCents() / 10)) {
            return false;
        }
        String first = WordMatch.loosen(c.getName()).split(" ")[0];
        return !first.isEmpty() && WordMatch.of(first).in(found.name() + " " + found.key());
    }

    /** The first due date on or after today, counted from the date you gave, so the 31st stays the 31st. */
    static LocalDate nextOnOrAfter(LocalDate start, Cadence cadence, LocalDate today) {
        if (start == null) {
            return null;
        }
        LocalDate next = start;
        for (int k = 1; next.isBefore(today) && k < 2000; k++) {
            next = cadence.after(start, k);
        }
        return next;
    }

    record Found(Item item, boolean active) {
    }

    /**
     * Charges grouped by who they go to, then followed through time: each charge joins
     * the run whose last charge was closest in amount, so a power bill can wander, two
     * Apple subscriptions stay apart, and a price rise continues the same run.
     */
    List<Found> find(List<Transaction> recent, LocalDate today) {
        Map<String, List<Transaction>> byPayee = new LinkedHashMap<>();
        for (Transaction t : recent) {
            if (expense(t)) {
                String key = key(t);
                if (!key.isBlank()) {
                    byPayee.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
                }
            }
        }
        List<Run> runs = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : byPayee.entrySet()) {
            for (List<Transaction> series : series(e.getValue())) {
                Run r = run(e.getKey(), series, today);
                if (r != null) {
                    runs.add(r);
                }
            }
        }
        // "Not recurring" remembers a payee and rhythm; the price only tells two apart.
        Map<String, Integer> sharing = new HashMap<>();
        for (Run r : runs) {
            sharing.merge(r.payee() + "|" + r.cadence(), 1, Integer::sum);
        }
        List<Found> found = new ArrayList<>();
        for (Run r : runs) {
            String base = r.payee() + "|" + r.cadence();
            String key = sharing.get(base) > 1 ? base + "|" + Math.round(r.amount() / 100.0) : base;
            Transaction last = r.last();
            Category c = last.getCategory();
            found.add(new Found(new Item(key, null, display(last, r.payee()), r.kind(), r.cadence().name(), r.amount(),
                    r.cadence().monthly(r.amount()), last.getDay(), r.next(), c == null ? null : c.getName(),
                    last.getAccount().getName(), MerchantLogos.domainFor(last.getMerchant(), last.getDescription()),
                    search(last, r.payee()), r.count()), r.active()));
        }
        return found;
    }

    private static boolean expense(Transaction t) {
        Category c = t.getCategory();
        boolean spending = c == null || c.getKind() == CategoryKind.EXPENSE;
        boolean catchAll = c == null || ReferenceData.UNCATEGORIZED.equals(c.getName());
        return t.getAmountCents() < 0 && spending && !t.isPending() && !t.getAccount().isHidden()
                && t.getPairedTxnId() == null
                && !(catchAll && TRANSFER.matcher(WordMatch.loosen(t.matchText())).find());
    }

    /** Runs of one payee's charges, in date order. */
    static List<List<Transaction>> series(List<Transaction> charges) {
        List<Transaction> byDay = new ArrayList<>(charges);
        byDay.sort(Comparator.comparing(Transaction::getDay));
        List<List<Transaction>> runs = new ArrayList<>();
        for (Transaction t : byDay) {
            long cents = -t.getAmountCents();
            List<Transaction> best = null;
            long bestDiff = Long.MAX_VALUE;
            for (List<Transaction> run : runs) {
                Transaction last = run.get(run.size() - 1);
                if (last.getDay().equals(t.getDay())) {
                    continue; // two charges on one day are two things
                }
                long before = -last.getAmountCents();
                long diff = Math.abs(cents - before);
                long tolerance = variable(t) ? before * 35 / 100 : Math.max(100, before * 3 / 100);
                if (diff <= tolerance && diff < bestDiff) {
                    best = run;
                    bestDiff = diff;
                }
            }
            if (best == null && !everyday(t)) {
                // A new price: the one run that was due about now carries on with it. Not for
                // everyday spending, where a ride near the renewal date isn't the membership.
                List<List<Transaction>> due = runs.stream().filter(run -> dueAround(run, t.getDay())
                        && within(-run.get(run.size() - 1).getAmountCents(), cents, 2)).toList();
                if (due.size() == 1) {
                    best = due.get(0);
                }
            }
            if (best == null) {
                runs.add(new ArrayList<>(List.of(t)));
            } else {
                best.add(t);
            }
        }
        return runs;
    }

    /** A run of two or more whose next charge was expected within a few days of {@code day}. */
    private static boolean dueAround(List<Transaction> run, LocalDate day) {
        if (run.size() < 2) {
            return false;
        }
        LocalDate last = run.get(run.size() - 1).getDay();
        Cadence cadence = cadence(ChronoUnit.DAYS.between(run.get(run.size() - 2).getDay(), last));
        if (cadence == null || cadence == Cadence.WEEKLY || cadence == Cadence.BIWEEKLY) {
            return false;
        }
        return Math.abs(ChronoUnit.DAYS.between(cadence.after(last), day)) <= 5;
    }

    record Run(String payee, Cadence cadence, RecurringKind kind, long amount, Transaction last, LocalDate next,
            boolean active, int count) {
    }

    private static Run run(String payee, List<Transaction> series, LocalDate today) {
        if (series.size() < 2) {
            return null;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < series.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(series.get(i - 1).getDay(), series.get(i).getDay()));
        }
        long gap = gaps.stream().sorted().toList().get(gaps.size() / 2);
        Cadence cadence = cadence(gap);
        if (cadence == null) {
            return null;
        }
        Transaction last = series.get(series.size() - 1);
        Category c = last.getCategory();
        boolean everyday = everyday(last);
        // Every week at the bagel place isn't a subscription: short rhythms need several
        // charges, and never for everyday spending.
        if ((cadence == Cadence.WEEKLY || cadence == Cadence.BIWEEKLY) && (everyday || series.size() < 4)) {
            return null;
        }
        // Most of the gaps have to agree, or it's just somewhere you go now and then.
        long steady = gaps.stream().filter(g -> cadence(g) == cadence).count();
        if (steady * 2 < gaps.size()) {
            return null;
        }
        RecurringKind kind = c != null && c.isFixedCost() ? RecurringKind.BILL
                : c != null && "Subscriptions".equals(c.getName()) ? RecurringKind.SUBSCRIPTION : RecurringKind.OTHER;
        long amount = -last.getAmountCents();
        LocalDate next = cadence.after(last.getDay());
        boolean active = !today.isAfter(next.plusDays(Math.max(5, gap / 2)));
        // Due and not through yet (or still pending): it's due now, not in the past.
        if (active && next.isBefore(today)) {
            next = today;
        }
        return new Run(payee, cadence, kind, amount, last, active ? next : null, active, series.size());
    }

    static Cadence cadence(long gap) {
        if (gap >= 6 && gap <= 8) {
            return Cadence.WEEKLY;
        }
        if (gap >= 13 && gap <= 15) {
            return Cadence.BIWEEKLY;
        }
        if (gap >= 26 && gap <= 35) {
            return Cadence.MONTHLY;
        }
        if (gap >= 85 && gap <= 97) {
            return Cadence.QUARTERLY;
        }
        if (gap >= 350 && gap <= 380) {
            return Cadence.YEARLY;
        }
        return null;
    }

    /** Neither amount more than {@code factor} times the other. */
    private static boolean within(long a, long b, int factor) {
        return a > 0 && b > 0 && a <= b * factor && b <= a * factor;
    }

    private static boolean everyday(Transaction t) {
        return t.getCategory() != null && EVERYDAY.contains(t.getCategory().getName());
    }

    private static boolean variable(Transaction t) {
        Category c = t.getCategory();
        return c != null && (c.isFixedCost() || VARIABLE.contains(c.getName()));
    }

    /**
     * Who a charge goes to, the same from one month to the next. A payment to a person
     * keeps the person's name, so a monthly Zelle to the landlord isn't lumped with every other Zelle.
     */
    static String key(Transaction t) {
        boolean person = ReferenceData.PEOPLE_WORDS.stream().anyMatch(w -> WordMatch.of(w).in(t.matchText()));
        if (person) {
            String[] words = WordMatch.loosen(t.getDescription()).split(" ");
            return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 5))).trim();
        }
        if (t.getMerchant() != null && !t.getMerchant().isBlank()) {
            return WordMatch.loosen(t.getMerchant());
        }
        return PaycheckService.payerKey(t.getDescription());
    }

    private static final Pattern PROCESSOR = Pattern.compile("^(TST\\*|SQ ?\\*|SQC\\*|PAYPAL ?\\*|PP\\*)\\s*");
    private static final Pattern TO_PERSON = Pattern.compile("\\bTO\\s+([A-Z][A-Z.'-]*(?:\\s+[A-Z][A-Z.'-]*){0,2})");

    /**
     * A readable name: the payee the bank gave, or the description without card-processor
     * prefixes, dates, store numbers and state codes ("NETFLIX.COM 866-579 CA" becomes
     * "Netflix.com"), or for a payment to a person, who it went to.
     */
    static String display(Transaction t, String fallback) {
        if (t.getMerchant() != null && !t.getMerchant().isBlank()) {
            return t.getMerchant().trim();
        }
        String upper = t.getDescription().trim().toUpperCase(Locale.ROOT);
        boolean person = ReferenceData.PEOPLE_WORDS.stream().anyMatch(w -> WordMatch.of(w).in(upper));
        if (person) {
            Matcher to = TO_PERSON.matcher(upper);
            if (to.find()) {
                String app = ReferenceData.PEOPLE_WORDS.stream().filter(w -> WordMatch.of(w).in(upper)).findFirst().orElse("");
                return title(app) + " to " + title(to.group(1));
            }
        }
        String text = PROCESSOR.matcher(upper).replaceFirst("");
        List<String> words = new ArrayList<>();
        for (String w : text.split("\\s+")) {
            if (!w.isEmpty() && w.chars().noneMatch(Character::isDigit) && !w.startsWith("#")) {
                words.add(w);
            }
        }
        // A trailing two-letter word is almost always a state.
        if (words.size() > 1 && words.get(words.size() - 1).matches("[A-Z]{2}")) {
            words.remove(words.size() - 1);
        }
        String name = title(String.join(" ", words.subList(0, Math.min(words.size(), 4))));
        return name.isEmpty() ? title(fallback) : name;
    }

    /** A word to find its charges in Activity: the payee's name, or the longest word that names it. */
    static String search(Transaction t, String payee) {
        if (t.getMerchant() != null && !t.getMerchant().isBlank()) {
            return t.getMerchant().trim();
        }
        String best = "";
        for (String w : payee.split(" ")) {
            if (w.length() > best.length() && ReferenceData.PEOPLE_WORDS.stream().noneMatch(p -> p.equals(w))) {
                best = w;
            }
        }
        return best.length() >= 3 ? best : payee;
    }

    private static String title(String text) {
        StringBuilder out = new StringBuilder();
        for (String w : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }

    // --- Hand-added and hidden ------------------------------------------------------

    @Transactional
    public void hide(String key) {
        Set<String> hidden = settings.hiddenRecurring();
        hidden.add(key);
        settings.setHiddenRecurring(hidden);
    }

    @Transactional
    public void showHidden() {
        settings.setHiddenRecurring(Set.of());
    }

    @Transactional
    public RecurringCharge save(Long id, String name, long amountCents, Cadence cadence, RecurringKind kind,
            LocalDate nextDate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give it a name.");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("The amount must be more than zero.");
        }
        RecurringCharge c = id == null ? new RecurringCharge(name.trim()) : charges.findById(id).orElseThrow();
        c.setName(name.trim());
        c.setAmountCents(amountCents);
        c.setCadence(cadence == null ? Cadence.MONTHLY : cadence);
        c.setKind(kind == null ? RecurringKind.SUBSCRIPTION : kind);
        c.setNextDate(nextDate);
        return charges.save(c);
    }

    @Transactional
    public void delete(Long id) {
        charges.deleteById(id);
    }
}
