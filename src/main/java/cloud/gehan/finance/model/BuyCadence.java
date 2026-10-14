package cloud.gehan.finance.model;

/** How often a recurring contribution lands. */
public enum BuyCadence {
    /** Every N days from the first contribution (14 is every other week). */
    EVERY_N_DAYS,
    /** The 15th and the last day of each month. */
    SEMIMONTHLY,
    MONTHLY
}
