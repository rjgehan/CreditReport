package cloud.gehan.finance.service;

import java.util.List;

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
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.repo.CategoryRepository;
import cloud.gehan.finance.repo.CategoryRuleRepository;

/**
 * The starting categories and the merchant rules that fill them. Seeded once into
 * an empty database; after that they are yours to rename, delete or add to.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReferenceData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceData.class);

    // Names the code relies on. Renaming one in the UI is fine; they are looked up
    // by name only to seed and as fallbacks.
    public static final String PAYCHECK = "Paycheck";
    public static final String INTEREST = "Interest & Dividends";
    public static final String OTHER_INCOME = "Other Income";
    public static final String UNCATEGORIZED = "Uncategorized";
    public static final String REFUNDS = "Refunds & Credits";
    public static final String SAVINGS = "Savings";
    public static final String INVESTING = "Investing";
    public static final String RETIREMENT = "Retirement";
    public static final String CARD_PAYMENT = "Credit Card Payment";
    public static final String TRANSFER = "Transfer";

    /** Built-in rules sit at priority 10; rules you add start at 100. */
    public static final int BUILT_IN_PRIORITY = 10;
    public static final int USER_PRIORITY = 100;

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
                    "SQ *", "RESTAURANT", "PIZZA", "CAFE", "COFFEE", "BAR & GRILL", "TACO BELL", "WENDY"),
            new RuleSeed("Transportation", AmountSign.ANY, "UBER", "LYFT", "E-ZPASS", "EZPASS", "PARKING", "MTA",
                    "NJ TRANSIT", "AMTRAK", "TOLL", "PARKMOBILE", "SPOTHERO"),
            new RuleSeed("Gas", AmountSign.ANY, "SHELL", "EXXON", "EXXONMOBIL", "MOBIL", "CHEVRON", "SUNOCO", "BP",
                    "WAWA", "SHEETZ", "CITGO", "SPEEDWAY", "QUICK CHEK", "VALERO"),
            new RuleSeed("Shopping", AmountSign.ANY, "AMAZON", "AMZN", "TARGET", "WALMART", "BEST BUY", "ETSY",
                    "EBAY", "APPLE STORE", "IKEA", "TJ MAXX", "TJMAXX", "MARSHALLS", "NORDSTROM", "MACY", "KOHL"),
            new RuleSeed("Utilities", AmountSign.OUT, "VERIZON", "AT&T", "COMCAST", "XFINITY", "PSE&G", "CON ED",
                    "CONEDISON", "T-MOBILE", "SPECTRUM", "OPTIMUM", "NATIONAL GRID", "JCP&L", "DUKE ENERGY",
                    "ELECTRIC", "WATER", "SEWER"),
            new RuleSeed("Health", AmountSign.ANY, "CVS", "WALGREENS", "PHARMACY", "RITE AID", "DENTAL", "MEDICAL",
                    "HOSPITAL", "QUEST DIAG", "LABCORP", "URGENT CARE"),
            new RuleSeed("Insurance", AmountSign.OUT, "GEICO", "STATE FARM", "PROGRESSIVE", "ALLSTATE", "LIBERTY MUTUAL",
                    "LEMONADE", "INSURANCE"),
            new RuleSeed("Housing", AmountSign.OUT, "RENT", "MORTGAGE", "HOA", "HOME DEPOT", "LOWE"),
            new RuleSeed("Entertainment", AmountSign.ANY, "STEAM", "TICKETMASTER", "STUBHUB", "AMC", "REGAL",
                    "PLAYSTATION", "XBOX", "NINTENDO", "SEATGEEK"),
            new RuleSeed("Travel", AmountSign.ANY, "AIRLINES", "DELTA AIR", "UNITED AIRLINES", "AMERICAN AIR",
                    "JETBLUE", "SOUTHWEST", "MARRIOTT", "HILTON", "HYATT", "AIRBNB", "EXPEDIA", "BOOKING.COM", "VRBO"),
            new RuleSeed("Personal Care", AmountSign.ANY, "SALON", "BARBER", "SEPHORA", "ULTA", "SPA"),
            new RuleSeed("Gifts & Donations", AmountSign.OUT, "DONATION", "GOFUNDME", "CHARITY"),
            new RuleSeed("Fees & Interest", AmountSign.OUT, "FEE", "INTEREST CHARGE", "LATE FEE", "OVERDRAFT",
                    "FINANCE CHARGE"),
            new RuleSeed("Taxes", AmountSign.OUT, "IRS", "TREAS TAX", "STATE TAX", "DMV"));

    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TransactionTemplate tx;

    public ReferenceData(CategoryRepository categories, CategoryRuleRepository rules, TransactionTemplate tx) {
        this.categories = categories;
        this.rules = rules;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> seed());
    }

    void seed() {
        if (categories.count() > 0) {
            return;
        }
        int order = 0;
        for (Seed seed : CATEGORIES) {
            categories.save(new Category(seed.name(), seed.kind(), colorFor(seed.kind()), seed.icon(), order++));
        }
        int count = 0;
        for (RuleSeed seed : RULES) {
            Category category = categories.findByNameIgnoreCase(seed.category()).orElseThrow();
            for (String pattern : seed.patterns()) {
                rules.save(new CategoryRule(pattern, category, seed.sign(), seed.accountType(), BUILT_IN_PRIORITY));
                count++;
            }
        }
        log.info("Seeded {} categories and {} categorization rules", CATEGORIES.size(), count);
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
