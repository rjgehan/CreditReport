package cloud.gehan.finance.model;

public enum GoalType {
    /** Put at least this much into savings categories every month. */
    MONTHLY_AMOUNT,
    /** Grow an account (or all savings accounts) to this balance. */
    BALANCE,
    /** Save at least this share of gross income each month. */
    SAVINGS_RATE
}
