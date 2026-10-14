package cloud.gehan.finance.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Every change to a holding's share count, in order. Replaying these backwards
 * from today's shares gives the shares held on any past day, which is how a
 * balance history is rebuilt from historical prices.
 */
@Entity
@Table(name = "holding_trade", indexes = @Index(columnList = "account_id,day"))
public class HoldingTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** Positive for a buy, negative for a sell or a downward correction. */
    private double shares;

    private Double price;

    private Long amountCents;

    @Column(nullable = false)
    private TradeSource source;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected HoldingTrade() {
    }

    public HoldingTrade(Account account, String symbol, LocalDate day, double shares, Double price, Long amountCents,
            TradeSource source) {
        this.account = account;
        this.symbol = symbol;
        this.day = day;
        this.shares = shares;
        this.price = price;
        this.amountCents = amountCents;
        this.source = source;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getSymbol() { return symbol; }
    public LocalDate getDay() { return day; }
    public double getShares() { return shares; }
    public Double getPrice() { return price; }
    public Long getAmountCents() { return amountCents; }
    public TradeSource getSource() { return source; }
}
