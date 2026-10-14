package cloud.gehan.finance.model;

/**
 * A job the app gives a category: where it files transfers, pay, card refunds and so
 * on. Roles stay with a category through renames, and when you delete one, its roles
 * move to the category you pick, so the app always knows where these things go.
 */
public enum CategoryRole {
    PAYCHECK(CategoryKind.INCOME, "pay from work"),
    OTHER_INCOME(CategoryKind.INCOME, "money in that isn't pay"),
    UNCATEGORIZED(CategoryKind.EXPENSE, "spending nothing else claims"),
    REFUNDS(CategoryKind.EXPENSE, "refunds and credits on cards"),
    LOANS(CategoryKind.EXPENSE, "loan payments"),
    SUBSCRIPTIONS(CategoryKind.EXPENSE, "subscriptions on Monthly expenses"),
    SAVINGS(CategoryKind.SAVINGS, "money moved into savings"),
    INVESTING(CategoryKind.SAVINGS, "money moved into investments"),
    RETIREMENT(CategoryKind.SAVINGS, "401(k) contributions"),
    CARD_PAYMENT(CategoryKind.TRANSFER, "credit card payments"),
    TRANSFER(CategoryKind.TRANSFER, "moves between your own accounts");

    private final CategoryKind kind;
    private final String job;

    CategoryRole(CategoryKind kind, String job) {
        this.kind = kind;
        this.job = job;
    }

    /** The kind a category needs to do this job. */
    public CategoryKind kind() {
        return kind;
    }

    /** What the app files there, in words. */
    public String job() {
        return job;
    }

    /**
     * Where leftovers land: the category holding it is the catch-all itself, so it can
     * only move to a category of its own (a fresh one), never into one you use for something.
     */
    public boolean catchAll() {
        return this == UNCATEGORIZED || this == OTHER_INCOME || this == REFUNDS;
    }

    /** The app works without it (Monthly expenses just doesn't split subscriptions out). */
    public boolean optional() {
        return this == SUBSCRIPTIONS;
    }
}
