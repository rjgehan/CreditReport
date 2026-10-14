package cloud.gehan.finance.model;

/** Who chose a transaction's category, from most to least authoritative. */
public enum CategorySource {
    /** You picked it. Nothing automatic ever changes it. */
    MANUAL,
    /** One of the rules on the Plan page matched. */
    RULE,
    /** The provider said so (Teller's transaction category). */
    PROVIDER,
    /** Paired with the opposite leg in another of your accounts. */
    TRANSFER_MATCH,
    /** A guess from the account type and the sign of the amount. */
    DEFAULT
}
