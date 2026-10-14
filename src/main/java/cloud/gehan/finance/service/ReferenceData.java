package cloud.gehan.finance.service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.AmountSign;
import cloud.gehan.finance.model.AppSetting;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRole;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AppSettingRepository;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * The starting categories and the merchant rules that fill them. Seeded once into
 * an empty database; after that they are yours to rename, delete or add to.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReferenceData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceData.class);

    // The starting names. The app finds these categories by their role (CategoryRole), not
    // their name, so every one of them can be renamed or deleted; names are only for seeding.
    public static final String PAYCHECK = "Paycheck";
    public static final String INTEREST = "Interest & Dividends";
    public static final String OTHER_INCOME = "Other Income";
    public static final String SIDE_INCOME = "Side Income";
    public static final String UNCATEGORIZED = "Uncategorized";
    public static final String REFUNDS = "Refunds & Credits";
    public static final String SAVINGS = "Savings";
    public static final String INVESTING = "Investing";
    public static final String RETIREMENT = "Retirement";
    public static final String CARD_PAYMENT = "Credit Card Payment";
    public static final String TRANSFER = "Transfer";
    public static final String LOANS = "Loans";

    /** Which starting category does each job, until you move it. */
    static final Map<CategoryRole, String> ROLE_NAMES = new EnumMap<>(Map.ofEntries(
            Map.entry(CategoryRole.PAYCHECK, PAYCHECK), Map.entry(CategoryRole.OTHER_INCOME, OTHER_INCOME),
            Map.entry(CategoryRole.UNCATEGORIZED, UNCATEGORIZED), Map.entry(CategoryRole.REFUNDS, REFUNDS),
            Map.entry(CategoryRole.LOANS, LOANS), Map.entry(CategoryRole.SUBSCRIPTIONS, "Subscriptions"),
            Map.entry(CategoryRole.SAVINGS, SAVINGS), Map.entry(CategoryRole.INVESTING, INVESTING),
            Map.entry(CategoryRole.RETIREMENT, RETIREMENT), Map.entry(CategoryRole.CARD_PAYMENT, CARD_PAYMENT),
            Map.entry(CategoryRole.TRANSFER, TRANSFER)));

    /** Built-in rules sit at priority 10; rules you add start at 100. */
    public static final int BUILT_IN_PRIORITY = 10;
    public static final int USER_PRIORITY = 100;
    /** Payments to people outrank the other built-in rules: "ZELLE TO JOES PIZZA" is still a person. */
    static final int PEOPLE_PRIORITY = 11;

    // Colour by what the money does, not by category: there are more categories
    // than a colour-blind-safe palette has hues, and every node is labelled.
    static final String INCOME_COLOR = "#2a78d6";
    static final String EXPENSE_COLOR = "#eb6834";
    static final String SAVINGS_COLOR = "#1baf7a";
    static final String TRANSFER_COLOR = "#898781";

    record Seed(String name, CategoryKind kind, String icon) {
    }

    static final List<Seed> CATEGORIES = List.of(
            new Seed(PAYCHECK, CategoryKind.INCOME, "💼"),
            new Seed(SIDE_INCOME, CategoryKind.INCOME, "🧰"),
            new Seed(INTEREST, CategoryKind.INCOME, "🏦"),
            new Seed(OTHER_INCOME, CategoryKind.INCOME, "💵"),
            new Seed("Housing", CategoryKind.EXPENSE, "🏠"),
            new Seed("Utilities", CategoryKind.EXPENSE, "💡"),
            new Seed("Groceries", CategoryKind.EXPENSE, "🛒"),
            new Seed("Dining", CategoryKind.EXPENSE, "🍽️"),
            new Seed("Transportation", CategoryKind.EXPENSE, "🚗"),
            new Seed("Gas", CategoryKind.EXPENSE, "⛽"),
            new Seed("Shopping", CategoryKind.EXPENSE, "🛍️"),
            new Seed("Subscriptions", CategoryKind.EXPENSE, "🔁"),
            new Seed("Health", CategoryKind.EXPENSE, "🩺"),
            new Seed("Insurance", CategoryKind.EXPENSE, "🛡️"),
            new Seed("Entertainment", CategoryKind.EXPENSE, "🎟️"),
            new Seed("Travel", CategoryKind.EXPENSE, "✈️"),
            new Seed("Personal Care", CategoryKind.EXPENSE, "💇"),
            new Seed("Gifts & Donations", CategoryKind.EXPENSE, "🎁"),
            new Seed("Education", CategoryKind.EXPENSE, "📚"),
            new Seed("Fees & Interest", CategoryKind.EXPENSE, "🧾"),
            new Seed("Taxes", CategoryKind.EXPENSE, "🏛️"),
            new Seed("Loans", CategoryKind.EXPENSE, "📄"),
            new Seed(REFUNDS, CategoryKind.EXPENSE, "↩️"),
            new Seed(UNCATEGORIZED, CategoryKind.EXPENSE, "❔"),
            new Seed(SAVINGS, CategoryKind.SAVINGS, "🐖"),
            new Seed(INVESTING, CategoryKind.SAVINGS, "📈"),
            new Seed(RETIREMENT, CategoryKind.SAVINGS, "🏖️"),
            new Seed(CARD_PAYMENT, CategoryKind.TRANSFER, "💳"),
            new Seed(TRANSFER, CategoryKind.TRANSFER, "🔄"));

    record RuleSeed(String category, AmountSign sign, AccountType accountType, String... patterns) {
        RuleSeed(String category, AmountSign sign, String... patterns) {
            this(category, sign, null, patterns);
        }
    }

    static final List<RuleSeed> RULES = List.of(
            new RuleSeed(PAYCHECK, AmountSign.IN, "PAYROLL", "DIRECT DEP", "DIR DEP", "DIRECT DEPOSIT", "SALARY", "ADP"),
            new RuleSeed(INTEREST, AmountSign.IN, "INTEREST", "DIVIDEND", "INTEREST PAID", "INT EARNED"),
            new RuleSeed(CARD_PAYMENT, AmountSign.ANY, "PAYMENT THANK YOU", "AUTOPAY", "AUTOMATIC PAYMENT",
                    "CREDIT CARD PAYMENT", "CRCARDPMT", "CARD PAYMENT", "EPAYMENT", "APPLECARD GSBANK", "AMEX EPAYMENT",
                    "CAPITAL ONE MOBILE PMT", "DISCOVER E-PAYMENT", "CHASE CREDIT CRD"),
            new RuleSeed(CARD_PAYMENT, AmountSign.IN, AccountType.CREDIT_CARD, "PAYMENT"),
            new RuleSeed(INVESTING, AmountSign.ANY, "WEBULL", "ROBINHOOD", "FIDELITY", "VANGUARD", "SCHWAB", "E TRADE",
                    "ETRADE", "M1 FINANCE", "BETTERMENT", "WEALTHFRONT", "COINBASE"),
            new RuleSeed(SAVINGS, AmountSign.OUT, "TRANSFER TO SAV", "TO SAVINGS", "SAVINGS TRANSFER"),
            new RuleSeed("Subscriptions", AmountSign.OUT, "NETFLIX", "SPOTIFY", "HULU", "DISNEY PLUS", "DISNEYPLUS",
                    "HBO MAX", "MAX.COM", "APPLE.COM/BILL", "YOUTUBE PREMIUM", "PARAMOUNT", "PEACOCK", "AUDIBLE",
                    "ICLOUD", "PATREON", "CHATGPT", "OPENAI", "ANTHROPIC", "CLAUDE.AI", "AMAZON PRIME"),
            new RuleSeed("Groceries", AmountSign.ANY, "WHOLE FOODS", "WHOLEFDS", "TRADER JOE", "KROGER", "SAFEWAY",
                    "WEGMANS", "PUBLIX", "ALDI", "SHOPRITE", "STOP & SHOP", "GIANT", "FOOD LION", "HARRIS TEETER",
                    "SPROUTS", "INSTACART", "H-E-B", "MARKET BASKET", "HANNAFORD", "COSTCO"),
            new RuleSeed("Dining", AmountSign.ANY, "STARBUCKS", "MCDONALD", "CHIPOTLE", "DOORDASH", "UBER EATS",
                    "UBEREATS", "GRUBHUB", "DUNKIN", "SWEETGREEN", "CHICK-FIL-A", "PANERA", "SHAKE SHACK", "TST",
                    "RESTAURANT", "PIZZA", "CAFE", "COFFEE", "BAR & GRILL", "TACO BELL", "WENDY"),
            new RuleSeed("Transportation", AmountSign.ANY, "UBER", "LYFT", "E-ZPASS", "EZPASS", "PARKING", "MTA",
                    "NJ TRANSIT", "AMTRAK", "TOLL", "PARKMOBILE", "SPOTHERO"),
            new RuleSeed("Gas", AmountSign.ANY, "SHELL", "EXXON", "EXXONMOBIL", "MOBIL", "CHEVRON", "SUNOCO", "BP",
                    "WAWA", "SHEETZ", "CITGO", "SPEEDWAY", "QUICK CHEK", "VALERO"),
            new RuleSeed("Shopping", AmountSign.ANY, "AMAZON", "AMZN", "TARGET", "WALMART", "BEST BUY", "ETSY",
                    "EBAY", "APPLE STORE", "IKEA", "TJ MAXX", "TJMAXX", "MARSHALLS", "NORDSTROM", "MACY", "KOHL",
                    "HOME DEPOT", "LOWE"),
            new RuleSeed("Utilities", AmountSign.OUT, "VERIZON", "AT&T", "COMCAST", "XFINITY", "PSE&G", "CON ED",
                    "CONEDISON", "T-MOBILE", "SPECTRUM", "OPTIMUM", "NATIONAL GRID", "JCP&L", "DUKE ENERGY",
                    "ELECTRIC", "WATER", "SEWER"),
            new RuleSeed("Health", AmountSign.ANY, "CVS", "WALGREENS", "PHARMACY", "RITE AID", "DENTAL", "MEDICAL",
                    "HOSPITAL", "QUEST DIAG", "LABCORP", "URGENT CARE"),
            new RuleSeed("Insurance", AmountSign.OUT, "GEICO", "STATE FARM", "PROGRESSIVE", "ALLSTATE", "LIBERTY MUTUAL",
                    "LEMONADE", "INSURANCE"),
            // Housing is a fixed cost (the same every month), so only rent-like things belong here.
            new RuleSeed("Housing", AmountSign.OUT, "RENT", "MORTGAGE", "HOA", "APPFOLIO", "RENTCAFE", "RENT CAFE",
                    "YARDI", "BUILDIUM", "CLICKPAY", "PAYLEASE", "ZEGO", "BILT RENT", "RENT PAYMENT", "RENT PMT"),
            new RuleSeed("Entertainment", AmountSign.ANY, "STEAM", "TICKETMASTER", "STUBHUB", "AMC", "REGAL",
                    "PLAYSTATION", "XBOX", "NINTENDO", "SEATGEEK"),
            new RuleSeed("Travel", AmountSign.ANY, "AIRLINES", "DELTA AIR", "UNITED AIRLINES", "AMERICAN AIR",
                    "JETBLUE", "SOUTHWEST", "MARRIOTT", "HILTON", "HYATT", "AIRBNB", "EXPEDIA", "BOOKING.COM", "VRBO",
                    "RENT-A-CAR", "RENT A CAR", "HERTZ", "AVIS", "ENTERPRISE RENT", "BUDGET RENT"),
            new RuleSeed("Personal Care", AmountSign.ANY, "SALON", "BARBER", "SEPHORA", "ULTA", "SPA"),
            new RuleSeed("Gifts & Donations", AmountSign.OUT, "DONATION", "GOFUNDME", "CHARITY"),
            new RuleSeed("Fees & Interest", AmountSign.OUT, "FEE", "INTEREST CHARGE", "LATE FEE", "OVERDRAFT",
                    "FINANCE CHARGE"),
            new RuleSeed("Taxes", AmountSign.OUT, "IRS", "TREAS TAX", "STATE TAX", "DMV"),
            new RuleSeed("Loans", AmountSign.OUT, "NELNET", "NAVIENT", "MOHELA", "AIDVANTAGE", "SALLIE MAE",
                    "EDFINANCIAL", "LOAN PMT", "LOAN PAYMENT", "AUTO LOAN", "STUDENT LN", "STUDENT LOAN", "DEPT EDUCATION",
                    "DEPT OF EDUCATION", "FEDLOAN"));

    /**
     * Venmo, Zelle and the like could be anything (dinner, rent, paying a friend back),
     * so nothing guesses at them, the iPhone shortcut included. They wait for you.
     */
    public static final List<String> PEOPLE_WORDS = List.of("VENMO", "ZELLE", "CASH APP", "CASHAPP", "APPLE CASH");
    static final List<RuleSeed> PEOPLE = List.of(
            new RuleSeed(UNCATEGORIZED, AmountSign.OUT, PEOPLE_WORDS.toArray(String[]::new)),
            new RuleSeed(OTHER_INCOME, AmountSign.IN, PEOPLE_WORDS.toArray(String[]::new)));

    /** Built-in rules added after the first release, put into existing databases once. */
    static final List<RuleSeed> ADDED_IN_3 = List.of(
            new RuleSeed("Housing", AmountSign.OUT, "APPFOLIO", "RENTCAFE", "RENT CAFE", "YARDI", "BUILDIUM", "CLICKPAY",
                    "PAYLEASE", "ZEGO", "BILT RENT", "RENT PAYMENT", "RENT PMT"),
            new RuleSeed(LOANS, AmountSign.OUT, "STUDENT LOAN", "DEPT EDUCATION", "DEPT OF EDUCATION", "FEDLOAN"));

    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TransactionTemplate tx;
    private final RuleService ruleService;
    private final AppSettingRepository settings;
    private final TransactionRepository txns;

    public ReferenceData(CategoryRepository categories, CategoryRuleRepository rules, TransactionTemplate tx,
            RuleService ruleService, AppSettingRepository settings, TransactionRepository txns) {
        this.categories = categories;
        this.rules = rules;
        this.tx = tx;
        this.ruleService = ruleService;
        this.settings = settings;
        this.txns = txns;
    }

    /** Bumped when rules start matching differently, so everything is filed again once. */
    static final String RULE_MATCHING = "rule-matching";
    static final String RULE_MATCHING_VERSION = "2";
    /** Bumped when the built-in rules change in a way existing databases need. */
    static final String BUILT_IN_RULES = "built-in-rules";
    static final String BUILT_IN_RULES_VERSION = "3";

    /** Earned income: what "income" means everywhere in the app. */
    static final Set<String> EARNED = Set.of(PAYCHECK, SIDE_INCOME);
    /** The same every month; left out of month-to-month comparisons. */
    static final Set<String> FIXED = Set.of("Housing", "Loans");

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            seed();
            ensureRoles();
            upgrade();
        });
    }

    /**
     * Brings a database from an earlier version up to date: categories that
     * predate the earned-income and fixed-cost flags get them from their name,
     * and Side Income is added if missing. Choices already made are left alone.
     */
    void upgrade() {
        // Only for databases from before Side Income existed; after that, deleting it sticks.
        if (setting(BUILT_IN_RULES) == null && categories.findByNameIgnoreCase(SIDE_INCOME).isEmpty()) {
            Category side = new Category(SIDE_INCOME, CategoryKind.INCOME, colorFor(CategoryKind.INCOME), "🧰", 1);
            side.setEarnedIncome(true);
            categories.save(side);
        }
        for (Category c : categories.findAll()) {
            if (c.getEarnedIncome() == null) {
                c.setEarnedIncome(c.getKind() == CategoryKind.INCOME && EARNED.contains(c.getName()));
            }
            if (c.getFixedCost() == null) {
                c.setFixedCost(c.getKind() == CategoryKind.EXPENSE && FIXED.contains(c.getName()));
            }
        }

        // Housing became a fixed cost, so the built-in rules that sent home-improvement
        // stores there move to Shopping, and car rentals (which "RENT" caught) get their own.
        boolean changed = false;
        Category shopping = categories.findByNameIgnoreCase("Shopping").orElse(null);
        Category travel = categories.findByNameIgnoreCase("Travel").orElse(null);
        List<CategoryRule> all = rules.findAll();
        for (CategoryRule r : all) {
            if (shopping != null && setting(BUILT_IN_RULES) == null && r.getPriority() == BUILT_IN_PRIORITY
                    && "Housing".equals(r.getCategory().getName())
                    && (r.getPattern().equals("HOME DEPOT") || r.getPattern().equals("LOWE"))) {
                r.setCategory(shopping);
                changed = true;
            }
        }
        // Only for databases from before these rules were seeded; one you delete stays deleted.
        if (travel != null && setting(BUILT_IN_RULES) == null) {
            Set<String> have = new HashSet<>();
            all.forEach(r -> have.add(r.getPattern()));
            for (String p : List.of("RENT-A-CAR", "RENT A CAR", "HERTZ", "AVIS", "ENTERPRISE RENT", "BUDGET RENT")) {
                if (!have.contains(p)) {
                    rules.save(new CategoryRule(p, travel, AmountSign.ANY, null, BUILT_IN_PRIORITY));
                    changed = true;
                }
            }
        }
        // Tapping "This is my pay" twice used to add the same rule twice. Keep the highest-ranked
        // copy of each of yours; built-in rules are never touched.
        Set<String> seen = new HashSet<>();
        for (CategoryRule r : rules.findAllByOrderByPriorityDescIdAsc()) {
            if (r.getPriority() < USER_PRIORITY) {
                continue;
            }
            String key = r.getPattern().trim().toUpperCase(Locale.ROOT) + "|" + r.getCategory().getId() + "|" + r.getSign()
                    + "|" + r.getAccountType();
            if (!seen.add(key)) {
                rules.delete(r);
                changed = true;
            }
        }
        // Rules now match across punctuation and numbers (WordMatch); deposits they missed get filed.
        if (!RULE_MATCHING_VERSION.equals(setting(RULE_MATCHING))) {
            settings.save(new AppSetting(RULE_MATCHING, RULE_MATCHING_VERSION));
            changed = true;
        }
        // Once: payments to people stop being guessed at, "SQ *" (Square takes payments for
        // every kind of business, not just cafes) stops meaning Dining, rent portals and more
        // student-loan servicers are known, and what you'd already filed by hand counts as reviewed.
        if (!BUILT_IN_RULES_VERSION.equals(setting(BUILT_IN_RULES))) {
            for (CategoryRule r : rules.findAll()) {
                if (r.getPriority() == BUILT_IN_PRIORITY && r.getPattern().equals("SQ *")
                        && "Dining".equals(r.getCategory().getName())) {
                    rules.delete(r);
                }
            }
            seedRules(ADDED_IN_3, BUILT_IN_PRIORITY);
            seedRules(PEOPLE, PEOPLE_PRIORITY);
            txns.markReviewed(CategorySource.MANUAL);
            // The shortcut's old prompt was too quick to say Dining. Those guesses go back to
            // being leftovers, for the rules and the stricter prompt to try again.
            for (Transaction t : txns.findByCategorySource(CategorySource.AI)) {
                if (t.getCategory() != null && "Dining".equals(t.getCategory().getName()) && !t.isReviewed()) {
                    t.setCategory(null);
                    t.setCategorySource(CategorySource.DEFAULT);
                }
            }
            settings.save(new AppSetting(BUILT_IN_RULES, BUILT_IN_RULES_VERSION));
            changed = true;
        }
        if (changed) {
            rules.flush();
            ruleService.reapply();
        }
    }

    /**
     * Every job has a category. Existing databases get the roles by the starting names;
     * a job whose category is missing (only possible before roles existed) gets a fresh one.
     * Subscriptions is optional: without it, Monthly expenses just doesn't split them out.
     */
    void ensureRoles() {
        List<Category> all = categories.findAll();
        for (CategoryRole role : CategoryRole.values()) {
            if (all.stream().anyMatch(c -> c.hasRole(role))) {
                continue;
            }
            Category c = categories.findByNameIgnoreCase(ROLE_NAMES.get(role))
                    .filter(found -> found.getKind() == role.kind()).orElse(null);
            if (c == null && role == CategoryRole.SUBSCRIPTIONS) {
                // Renamed before roles existed: it's wherever the built-in NETFLIX rule files to.
                c = rules.findAll().stream()
                        .filter(r -> r.getPriority() == BUILT_IN_PRIORITY && "NETFLIX".equals(r.getPattern()))
                        .map(CategoryRule::getCategory).filter(found -> found.getKind() == role.kind())
                        .filter(found -> found.getRoles().isEmpty()).findFirst().orElse(null);
            }
            if (c == null && role != CategoryRole.SUBSCRIPTIONS) {
                String icon = CATEGORIES.stream().filter(seed -> seed.name().equals(ROLE_NAMES.get(role))).map(Seed::icon)
                        .findFirst().orElse(null);
                String name = categories.findByNameIgnoreCase(ROLE_NAMES.get(role)).isPresent()
                        ? ROLE_NAMES.get(role) + " (" + role.kind().name().toLowerCase(Locale.ROOT) + ")" : ROLE_NAMES.get(role);
                c = new Category(name, role.kind(), colorFor(role.kind()), icon, categories.findAll().size());
                c.setEarnedIncome(role == CategoryRole.PAYCHECK);
                c.setFixedCost(role == CategoryRole.LOANS);
                c = categories.save(c);
                all.add(c);
            }
            if (c != null) {
                c.addRoles(List.of(role));
            }
        }
    }

    void seed() {
        if (categories.count() > 0) {
            return;
        }
        int order = 0;
        for (Seed seed : CATEGORIES) {
            categories.save(new Category(seed.name(), seed.kind(), colorFor(seed.kind()), seed.icon(), order++));
        }
        int count = seedRules(RULES, BUILT_IN_PRIORITY) + seedRules(PEOPLE, PEOPLE_PRIORITY);
        settings.save(new AppSetting(BUILT_IN_RULES, BUILT_IN_RULES_VERSION));
        log.info("Seeded {} categories and {} categorization rules", CATEGORIES.size(), count);
    }

    /** Adds each rule that isn't there already; a seed whose category was deleted is skipped. */
    private int seedRules(List<RuleSeed> seeds, int priority) {
        Set<String> have = new HashSet<>();
        for (CategoryRule r : rules.findAll()) {
            have.add(r.getPattern() + "|" + r.getCategory().getId() + "|" + r.getSign());
        }
        int count = 0;
        for (RuleSeed seed : seeds) {
            Category category = categories.findByNameIgnoreCase(seed.category()).orElse(null);
            if (category == null) {
                continue;
            }
            for (String pattern : seed.patterns()) {
                if (have.add(pattern + "|" + category.getId() + "|" + seed.sign())) {
                    rules.save(new CategoryRule(pattern, category, seed.sign(), seed.accountType(), priority));
                    count++;
                }
            }
        }
        return count;
    }

    private String setting(String key) {
        return settings.findById(key).map(AppSetting::getValue).orElse(null);
    }

    public static String colorFor(CategoryKind kind) {
        return switch (kind) {
            case INCOME -> INCOME_COLOR;
            case EXPENSE -> EXPENSE_COLOR;
            case SAVINGS -> SAVINGS_COLOR;
            case TRANSFER -> TRANSFER_COLOR;
        };
    }
}
