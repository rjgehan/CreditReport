package cloud.gehan.finance.model;

/**
 * Decides where a category's money lands in the reports.
 *
 * <p>TRANSFER is the important one: credit card payments and moves between your
 * own accounts are real transactions but neither income nor spending, and counting
 * them would double every card purchase.
 */
public enum CategoryKind {
    INCOME,
    EXPENSE,
    SAVINGS,
    TRANSFER
}
