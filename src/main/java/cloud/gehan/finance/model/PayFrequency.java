package cloud.gehan.finance.model;

public enum PayFrequency {
    WEEKLY(52),
    BIWEEKLY(26),
    SEMIMONTHLY(24),
    MONTHLY(12);

    private final int perYear;

    PayFrequency(int perYear) {
        this.perYear = perYear;
    }

    public int perYear() {
        return perYear;
    }
}
