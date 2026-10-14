package cloud.gehan.finance.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "savings_goal")
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalType type;

    /** MONTHLY_AMOUNT and BALANCE. */
    private Long targetCents;

    /** SAVINGS_RATE, as a percentage of gross income. */
    private Double targetPercent;

    /** BALANCE: the account to watch; null means every savings-like account. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id")
    private Account account;

    /** MONTHLY_AMOUNT: the savings category that counts; null means all of them. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private Category category;

    /** BALANCE: optional deadline, which turns progress into a required monthly pace. */
    private LocalDate targetDate;

    protected SavingsGoal() {
    }

    public SavingsGoal(String name, GoalType type) {
        this.name = name;
        this.type = type;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public GoalType getType() { return type; }
    public void setType(GoalType type) { this.type = type; }
    public Long getTargetCents() { return targetCents; }
    public void setTargetCents(Long targetCents) { this.targetCents = targetCents; }
    public Double getTargetPercent() { return targetPercent; }
    public void setTargetPercent(Double targetPercent) { this.targetPercent = targetPercent; }
    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
}
