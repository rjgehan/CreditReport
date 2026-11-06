package cloud.gehan.finance.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Something Claude wrote about your money: the daily take on Home, a week's recap,
 * a month's review. One per kind and period; a newer one replaces it.
 */
@Entity
@Table(name = "insight")
public class Insight {

    public enum Kind { TAKE, WEEK, MONTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Kind kind;

    /** A day for TAKE (2026-11-06), the week's Monday for WEEK, YYYY-MM for MONTH. */
    @Column(nullable = false)
    private String period;

    /** TAKE only: on_track, watch or over. */
    private String status;

    @Column(length = 300)
    private String headline;

    /** Lines, one per line. */
    @Column(length = 4000)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    protected Insight() {
    }

    public Insight(Kind kind, String period) {
        this.kind = kind;
        this.period = period;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public String getPeriod() { return period; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
