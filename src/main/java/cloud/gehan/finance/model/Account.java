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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Balances are signed from your point of view: what you own is positive, what you
 * owe is negative. A credit card carrying 300 dollars has a balance of -30000
 * cents whichever provider reported it.
 */
@Entity
@Table(name = "account", uniqueConstraints = @UniqueConstraint(columnNames = {"connection_id", "external_id"}))
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for an account you keep up to date by hand. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "connection_id")
    private Connection connection;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String name;

    private String institution;

    /** Last four digits, when the provider gives them. */
    private String mask;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    private long balanceCents;
    private Long availableCents;
    private Long creditLimitCents;
    private String currency = "USD";

    // Credit card statement details, typed in on the Accounts page.
    private Long statementBalanceCents;
    private Long minimumPaymentCents;
    private LocalDate paymentDueDate;
    private Double aprPercent;

    /** Uninvested cash in an account valued from its holdings. */
    private Long cashCents;

    /** Hidden accounts stay synced but drop out of every total. */
    private boolean hidden;

    private Instant balanceUpdatedAt;

    protected Account() {
    }

    public Account(Connection connection, String externalId, String name, AccountType type) {
        this.connection = connection;
        this.externalId = externalId;
        this.name = name;
        this.type = type;
    }

    public boolean isManual() {
        return connection == null || connection.getProvider() == ProviderType.MANUAL;
    }

    public Long getId() { return id; }
    public Connection getConnection() { return connection; }
    public String getExternalId() { return externalId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getMask() { return mask; }
    public void setMask(String mask) { this.mask = mask; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(long balanceCents) { this.balanceCents = balanceCents; }
    public Long getAvailableCents() { return availableCents; }
    public void setAvailableCents(Long availableCents) { this.availableCents = availableCents; }
    public Long getCreditLimitCents() { return creditLimitCents; }
    public void setCreditLimitCents(Long creditLimitCents) { this.creditLimitCents = creditLimitCents; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getStatementBalanceCents() { return statementBalanceCents; }
    public void setStatementBalanceCents(Long statementBalanceCents) { this.statementBalanceCents = statementBalanceCents; }
    public Long getMinimumPaymentCents() { return minimumPaymentCents; }
    public void setMinimumPaymentCents(Long minimumPaymentCents) { this.minimumPaymentCents = minimumPaymentCents; }
    public LocalDate getPaymentDueDate() { return paymentDueDate; }
    public void setPaymentDueDate(LocalDate paymentDueDate) { this.paymentDueDate = paymentDueDate; }
    public Double getAprPercent() { return aprPercent; }
    public void setAprPercent(Double aprPercent) { this.aprPercent = aprPercent; }
    public Long getCashCents() { return cashCents; }
    public void setCashCents(Long cashCents) { this.cashCents = cashCents; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public Instant getBalanceUpdatedAt() { return balanceUpdatedAt; }
    public void setBalanceUpdatedAt(Instant balanceUpdatedAt) { this.balanceUpdatedAt = balanceUpdatedAt; }
}
