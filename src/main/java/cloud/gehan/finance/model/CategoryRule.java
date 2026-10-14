package cloud.gehan.finance.model;

import java.time.Instant;

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

/**
 * "Descriptions containing WHOLE FOODS are Groceries." Matched as whole words,
 * case-insensitively, against the description and merchant together.
 */
@Entity
@Table(name = "category_rule")
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String pattern;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmountSign sign = AmountSign.ANY;

    /** Only applies to accounts of this type, when set. */
    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    /** Higher wins. Rules you create start above the built-in ones. */
    private int priority;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected CategoryRule() {
    }

    public CategoryRule(String pattern, Category category, AmountSign sign, AccountType accountType, int priority) {
        this.pattern = pattern;
        this.category = category;
        this.sign = sign;
        this.accountType = accountType;
        this.priority = priority;
    }

    public Long getId() { return id; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public AmountSign getSign() { return sign; }
    public void setSign(AmountSign sign) { this.sign = sign; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Instant getCreatedAt() { return createdAt; }
}
