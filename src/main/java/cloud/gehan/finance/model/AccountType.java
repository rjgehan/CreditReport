package cloud.gehan.finance.model;

public enum AccountType {
    CHECKING("Cash"),
    SAVINGS("Cash"),
    CREDIT_CARD("Credit"),
    BROKERAGE("Investments"),
    RETIREMENT("Retirement"),
    LOAN("Loans"),
    OTHER("Other");

    private final String group;

    AccountType(String group) {
        this.group = group;
    }

    /** The heading this account sits under on the Accounts page. */
    public String group() {
        return group;
    }

    /** Owed money: shown as a negative contribution to net worth. */
    public boolean liability() {
        return this == CREDIT_CARD || this == LOAN;
    }

    /** Where money goes to be kept rather than spent. Moving money in counts as saving. */
    public boolean savingsLike() {
        return this == SAVINGS || this == BROKERAGE || this == RETIREMENT;
    }
}
