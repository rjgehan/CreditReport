package cloud.gehan.finance.model;

import java.time.Instant;

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

/**
 * Shares of one ticker in one account. An account with holdings is valued from
 * them (shares times the latest price, plus the account's cash) instead of from
 * a balance someone typed in.
 */
@Entity
@Table(name = "holding", uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "symbol"}))
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(nullable = false)
    private String symbol;

    private String name;

    private double shares;

    /** What was paid for the shares in total, if you know it; enables gain/loss. */
    private Long costBasisCents;

    private Double lastPrice;
    private Double previousClose;
    private Instant priceUpdatedAt;

    protected Holding() {
    }

    public Holding(Account account, String symbol) {
        this.account = account;
        this.symbol = symbol;
    }

    public long valueCents() {
        return lastPrice == null ? 0 : Math.round(shares * lastPrice * 100);
    }

    /** Today's move in dollars terms, from yesterday's close. */
    public long dayChangeCents() {
        return lastPrice == null || previousClose == null ? 0 : Math.round(shares * (lastPrice - previousClose) * 100);
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getShares() { return shares; }
    public void setShares(double shares) { this.shares = shares; }
    public Long getCostBasisCents() { return costBasisCents; }
    public void setCostBasisCents(Long costBasisCents) { this.costBasisCents = costBasisCents; }
    public Double getLastPrice() { return lastPrice; }
    public void setLastPrice(Double lastPrice) { this.lastPrice = lastPrice; }
    public Double getPreviousClose() { return previousClose; }
    public void setPreviousClose(Double previousClose) { this.previousClose = previousClose; }
    public Instant getPriceUpdatedAt() { return priceUpdatedAt; }
    public void setPriceUpdatedAt(Instant priceUpdatedAt) { this.priceUpdatedAt = priceUpdatedAt; }
}
