package cloud.gehan.finance.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Amounts are signed from your point of view: negative is money leaving the
 * account, positive is money arriving. Providers that report card purchases as
 * positive numbers are flipped on the way in.
 */
@Entity
@Table(name = "txn",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "external_id"}),
        indexes = {@Index(columnList = "day"), @Index(columnList = "category_id")})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    private long amountCents;

    @Column(nullable = false, length = 500)
    private String description;

    private String merchant;

    /** What the provider called it, e.g. teller:groceries. Input to the categorizer. */
    private String providerCategory;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    private CategorySource categorySource = CategorySource.DEFAULT;

    private boolean pending;

    /** The other leg when this is one half of a move between your own accounts. */
    private Long pairedTxnId;

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Transaction() {
    }

    public Transaction(Account account, String externalId, LocalDate day, long amountCents, String description) {
        this.account = account;
        this.externalId = externalId;
        this.day = day;
        this.amountCents = amountCents;
        this.description = description;
    }

    /** Description and merchant together, which is what rules match against. */
    public String matchText() {
        return merchant == null || merchant.isBlank() ? description : description + " " + merchant;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getExternalId() { return externalId; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public String getProviderCategory() { return providerCategory; }
    public void setProviderCategory(String providerCategory) { this.providerCategory = providerCategory; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public CategorySource getCategorySource() { return categorySource; }
    public void setCategorySource(CategorySource categorySource) { this.categorySource = categorySource; }
    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }
    public Long getPairedTxnId() { return pairedTxnId; }
    public void setPairedTxnId(Long pairedTxnId) { this.pairedTxnId = pairedTxnId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
}
