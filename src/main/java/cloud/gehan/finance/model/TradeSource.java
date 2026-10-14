package cloud.gehan.finance.model;

public enum TradeSource {
    /** Applied automatically from a recurring contribution. */
    RECURRING,
    /** Entered by hand: a buy or sell. */
    MANUAL,
    /** A share count corrected to match a statement. */
    ADJUSTMENT
}
