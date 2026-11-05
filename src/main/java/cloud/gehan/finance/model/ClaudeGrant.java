package cloud.gehan.finance.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Access you allowed one app: its current access and refresh tokens, kept only as
 * hashes. Deleting it disconnects the app on its next request.
 */
@Entity
@Table(name = "claude_grant")
public class ClaudeGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String clientName;

    @Column(nullable = false, unique = true)
    private String accessHash;

    @Column(nullable = false)
    private Instant accessExpiresAt;

    @Column(nullable = false, unique = true)
    private String refreshHash;

    @Column(nullable = false)
    private Instant refreshExpiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    protected ClaudeGrant() {
    }

    public ClaudeGrant(String clientId, String clientName) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getClientId() { return clientId; }
    public String getClientName() { return clientName; }
    public String getAccessHash() { return accessHash; }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public String getRefreshHash() { return refreshHash; }
    public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public void setTokens(String accessHash, Instant accessExpiresAt, String refreshHash, Instant refreshExpiresAt) {
        this.accessHash = accessHash;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshHash = refreshHash;
        this.refreshExpiresAt = refreshExpiresAt;
    }
}
