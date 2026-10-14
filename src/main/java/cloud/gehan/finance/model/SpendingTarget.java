package cloud.gehan.finance.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** A monthly cap on one expense category. */
@Entity
@Table(name = "spending_target")
public class SpendingTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", unique = true)
    private Category category;

    private long monthlyLimitCents;

    protected SpendingTarget() {
    }

    public SpendingTarget(Category category, long monthlyLimitCents) {
        this.category = category;
        this.monthlyLimitCents = monthlyLimitCents;
    }

    public Long getId() { return id; }
    public Category getCategory() { return category; }
    public long getMonthlyLimitCents() { return monthlyLimitCents; }
    public void setMonthlyLimitCents(long monthlyLimitCents) { this.monthlyLimitCents = monthlyLimitCents; }
}
