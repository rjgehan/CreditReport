package cloud.gehan.finance.model;

import java.time.LocalDate;

/** How often a regular charge comes round. */
public enum Cadence {
    WEEKLY(52),
    BIWEEKLY(26),
    MONTHLY(12),
    QUARTERLY(4),
    YEARLY(1);

    private final int perYear;

    Cadence(int perYear) {
        this.perYear = perYear;
    }

    public int perYear() {
        return perYear;
    }

    /** What one charge comes to a month. */
    public long monthly(long cents) {
        return Math.round(cents * perYear / 12.0);
    }

    /** The charge after one on {@code day}. */
    public LocalDate after(LocalDate day) {
        return after(day, 1);
    }

    /** The {@code n}th charge after one on {@code day}, counted from it (Jan 31 + 2 months is Mar 31). */
    public LocalDate after(LocalDate day, int n) {
        return switch (this) {
            case WEEKLY -> day.plusWeeks(n);
            case BIWEEKLY -> day.plusWeeks(2L * n);
            case MONTHLY -> day.plusMonths(n);
            case QUARTERLY -> day.plusMonths(3L * n);
            case YEARLY -> day.plusYears(n);
        };
    }
}
