package cloud.gehan.finance.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A charge Claude thinks you should look at, or a change it suggests (a rule, a target) for you to approve. */
@Entity
@Table(name = "insight_item")
public class InsightItem {

    public enum Kind { CHARGE, RULE, TARGET }

    public enum State { OPEN, DONE, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private State state = State.OPEN;

    /** CHARGE: the transaction. */
    private Long transactionId;

    /** RULE: the words to match. */
    private String pattern;

    /** RULE and TARGET: the category. */
    private Long categoryId;

    /** TARGET: the suggested monthly limit. */
    private Long limitCents;

    /** Why, in a sentence. */
    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected InsightItem() {
    }

    public InsightItem(Kind kind, String reason) {
        this.kind = kind;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getLimitCents() { return limitCents; }
    public void setLimitCents(Long limitCents) { this.limitCents = limitCents; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
