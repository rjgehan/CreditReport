package cloud.gehan.finance.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** One balance per account per day; the net worth chart is built from these. */
@Entity
@Table(name = "balance_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "day"}))
public class BalanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    private long balanceCents;

    protected BalanceSnapshot() {
    }

    public BalanceSnapshot(Account account, LocalDate day, long balanceCents) {
        this.account = account;
        this.day = day;
        this.balanceCents = balanceCents;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public LocalDate getDay() { return day; }
    public long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(long balanceCents) { this.balanceCents = balanceCents; }
}
