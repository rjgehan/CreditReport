package cloud.gehan.finance.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An app that registered itself to ask for access (claude.ai, Claude Code). Registering grants nothing. */
@Entity
@Table(name = "claude_client")
public class ClaudeClient {

    @Id
    private String clientId;

    @Column(nullable = false)
    private String name;

    /** One per line: where the sign-in may send you back to. */
    @Column(nullable = false, length = 2000)
    private String redirectUris;

    @Column(nullable = false)
    private Instant createdAt;

    protected ClaudeClient() {
    }

    public ClaudeClient(String clientId, String name, List<String> redirectUris) {
        this.clientId = clientId;
        this.name = name;
        this.redirectUris = String.join("\n", redirectUris);
        this.createdAt = Instant.now();
    }

    public String getClientId() { return clientId; }
    public String getName() { return name; }
    public List<String> getRedirectUris() { return Arrays.asList(redirectUris.split("\n")); }
    public Instant getCreatedAt() { return createdAt; }
}
