package cloud.gehan.finance.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One login at one provider: a Teller enrollment or a SimpleFIN access URL. A single
 * connection usually brings several accounts with it.
 */
@Entity
@Table(name = "connection")
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private ProviderType provider;

    @Column(nullable = false)
    private String displayName;

    /** Provider credential, AES-GCM encrypted. Never leaves the server. */
    @Column(length = 4000)
    private String secretEncrypted;

    /** Teller enrollment id, which Teller Connect needs to repair a broken login. */
    private String externalId;

    /** Incremental sync cursor, for providers that have one. */
    @Column(length = 2000)
    private String syncCursor;

    @Column(nullable = false)
    private ConnectionStatus status = ConnectionStatus.OK;

    @Column(length = 2000)
    private String lastError;

    private Instant lastSyncedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Connection() {
    }

    public Connection(ProviderType provider, String displayName) {
        this.provider = provider;
        this.displayName = displayName;
    }

    public Long getId() { return id; }
    public ProviderType getProvider() { return provider; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSecretEncrypted() { return secretEncrypted; }
    public void setSecretEncrypted(String secretEncrypted) { this.secretEncrypted = secretEncrypted; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getSyncCursor() { return syncCursor; }
    public void setSyncCursor(String syncCursor) { this.syncCursor = syncCursor; }
    public ConnectionStatus getStatus() { return status; }
    public void setStatus(ConnectionStatus status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
