package cloud.gehan.finance.model;

/** Where a regular charge is listed on Monthly expenses. */
public enum RecurringKind {
    /** Rent, loans: the same every month and not optional. */
    BILL,
    /** Streaming, apps, memberships: the ones you could cancel. */
    SUBSCRIPTION,
    /** Phone, utilities, insurance, a gym. */
    OTHER
}
