package cloud.gehan.finance.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A regular charge you added by hand because the bank history can't show it: a
 * yearly subscription not due yet, or one paid from an account that isn't linked.
 */
@Entity
@Table(name = "recurring_charge")
public class RecurringCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Each charge, as a positive amount. */
    private long amountCents;

    @Column(nullable = false)
    private Cadence cadence = Cadence.MONTHLY;

    @Column(nullable = false)
    private RecurringKind kind = RecurringKind.SUBSCRIPTION;

    /** When it next comes out, if you know. */
    private LocalDate nextDate;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected RecurringCharge() {
    }

    public RecurringCharge(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public Cadence getCadence() { return cadence; }
    public void setCadence(Cadence cadence) { this.cadence = cadence; }
    public RecurringKind getKind() { return kind; }
    public void setKind(RecurringKind kind) { this.kind = kind; }
    public LocalDate getNextDate() { return nextDate; }
    public void setNextDate(LocalDate nextDate) { this.nextDate = nextDate; }
    public Instant getCreatedAt() { return createdAt; }
}
