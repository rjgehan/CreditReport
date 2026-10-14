package cloud.gehan.finance.model;

/** Who chose a transaction's category, from most to least authoritative. */
public enum CategorySource {
    /** You picked it. Nothing automatic ever changes it. */
    MANUAL,
    /** One of your rules matched (Activity, Rules). */
    RULE,
    /** The provider said so (none of the current providers send categories). */
    PROVIDER,
    /** Filed by Apple Intelligence through the iPhone shortcut, for something no rule or provider covered. */
    AI,
    /** Paired with the opposite leg in another of your accounts. */
    TRANSFER_MATCH,
    /** A guess from the account type and the sign of the amount. */
    DEFAULT
}
