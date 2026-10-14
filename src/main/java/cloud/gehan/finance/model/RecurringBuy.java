package cloud.gehan.finance.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The same amount into the same fund on a schedule, like a 401(k) contribution
 * every payday. Each due date buys amount / that day's close in shares, so the
 * account tracks itself without a connection.
 */
@Entity
@Table(name = "recurring_buy")
public class RecurringBuy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(nullable = false)
    private String symbol;

    private long amountCents;

    @Column(nullable = false)
    private BuyCadence cadence = BuyCadence.EVERY_N_DAYS;

    /** EVERY_N_DAYS only. */
    private int intervalDays = 14;

    /** The first contribution this schedule should apply. */
    @Column(nullable = false)
    private LocalDate startDate;

    /** The last contribution already applied; null before the first. */
    private LocalDate lastApplied;

    private boolean active = true;

    protected RecurringBuy() {
    }

    public RecurringBuy(Account account, String symbol, long amountCents, LocalDate startDate) {
        this.account = account;
        this.symbol = symbol;
        this.amountCents = amountCents;
        this.startDate = startDate;
    }

    /** Contribution dates after {@code lastApplied} (or from the start) up to and including {@code through}. */
    public List<LocalDate> dueThrough(LocalDate through) {
        List<LocalDate> due = new ArrayList<>();
        LocalDate d = startDate;
        int guard = 0;
        while (!d.isAfter(through) && guard++ < 5000) {
            if (lastApplied == null || d.isAfter(lastApplied)) {
                due.add(d);
            }
            d = next(d);
        }
        return due;
    }

    public LocalDate nextAfter(LocalDate day) {
        LocalDate d = startDate;
        int guard = 0;
        while (!d.isAfter(day) && guard++ < 5000) {
            d = next(d);
        }
        return d;
    }

    private LocalDate next(LocalDate d) {
        return switch (cadence) {
            case EVERY_N_DAYS -> d.plusDays(Math.max(1, intervalDays));
            case MONTHLY -> startDate.plusMonths(monthsBetween(startDate, d) + 1);
            case SEMIMONTHLY -> d.getDayOfMonth() < 15 ? d.withDayOfMonth(15)
                    : d.getDayOfMonth() < d.lengthOfMonth() ? d.withDayOfMonth(d.lengthOfMonth())
                    : d.plusDays(1).withDayOfMonth(15);
        };
    }

    private static long monthsBetween(LocalDate a, LocalDate b) {
        return (b.getYear() - a.getYear()) * 12L + (b.getMonthValue() - a.getMonthValue());
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public BuyCadence getCadence() { return cadence; }
    public void setCadence(BuyCadence cadence) { this.cadence = cadence; }
    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getLastApplied() { return lastApplied; }
    public void setLastApplied(LocalDate lastApplied) { this.lastApplied = lastApplied; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
