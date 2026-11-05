package cloud.gehan.finance.service.assistant;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.model.ClaudeClient;
import cloud.gehan.finance.model.ClaudeGrant;
import cloud.gehan.finance.repo.ClaudeClientRepository;
import cloud.gehan.finance.repo.ClaudeGrantRepository;

/**
 * OAuth 2.1 for one person and one kind of app: Claude signs in the way it does
 * with any connector (dynamic registration, PKCE, a consent screen), and gets a
 * token that reads the MCP tools and nothing else.
 *
 * <p>The consent screen sits behind your normal sign-in, on Tailscale. Only the
 * server-to-server parts (registration, the token endpoint, /mcp) need to be
 * reachable by Anthropic, and every one of them is useless without a token
 * that came from that screen.
 */
@Service
public class ClaudeOAuth {

    public static final String SCOPE = "finance:read";

    static final Duration CODE_LIFE = Duration.ofMinutes(5);
    static final Duration ACCESS_LIFE = Duration.ofHours(1);
    static final Duration REFRESH_LIFE = Duration.ofDays(30);
    static final int MAX_CLIENTS = 20;

    private final ClaudeClientRepository clients;
    private final ClaudeGrantRepository grants;
    private final SecureRandom random = new SecureRandom();
    /** Codes live for minutes and are used once; an app restart in between just means connecting again. */
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();

    public ClaudeOAuth(ClaudeClientRepository clients, ClaudeGrantRepository grants) {
        this.clients = clients;
        this.grants = grants;
    }

    /** Something wrong with a request, as an OAuth error code and a description. */
    public static class Refused extends RuntimeException {
        private final String code;
        private final boolean toApp;

        public Refused(String code, String description) {
            this(code, description, false);
        }

        /** @param toApp safe to send back to the app's redirect address (it checked out) */
        Refused(String code, String description, boolean toApp) {
            super(description);
            this.code = code;
            this.toApp = toApp;
        }

        public String code() {
            return code;
        }

        public boolean toApp() {
            return toApp;
        }
    }

    // --- Registration -------------------------------------------------------------------

    /**
     * Only Claude's own sign-in pages, and the local ones Claude Code opens on your
     * computer, may receive a code. Anything else could be someone fishing for one.
     */
    public static boolean allowedRedirect(String uri) {
        try {
            URI u = URI.create(uri);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (u.getFragment() != null || u.getUserInfo() != null) {
                return false;
            }
            if ("https".equals(u.getScheme())) {
                return host.equals("claude.ai") || host.endsWith(".claude.ai")
                        || host.equals("claude.com") || host.endsWith(".claude.com");
            }
            return "http".equals(u.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Transactional
    public ClaudeClient register(String name, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new Refused("invalid_redirect_uri", "redirect_uris is required");
        }
        for (String uri : redirectUris) {
            if (uri == null || uri.length() > 500 || !allowedRedirect(uri)) {
                throw new Refused("invalid_redirect_uri", "Only Claude's redirect addresses are accepted: " + uri);
            }
        }
        String clean = name == null || name.isBlank() ? "Claude" : name.strip();
        clean = clean.length() > 80 ? clean.substring(0, 80) : clean;
        // Registration is open to anyone who can reach it, so keep the list short:
        // the oldest that never got connected make room.
        List<ClaudeClient> all = clients.findAllByOrderByCreatedAtAsc();
        for (int i = 0; i < all.size() && all.size() - i >= MAX_CLIENTS; i++) {
            if (!grants.existsByClientId(all.get(i).getClientId())) {
                clients.delete(all.get(i));
            }
        }
        if (clients.count() >= MAX_CLIENTS) {
            throw new Refused("invalid_client_metadata", "Too many apps registered. Disconnect one in the app first.");
        }
        return clients.save(new ClaudeClient("c_" + token(16), clean, redirectUris));
    }

    // --- Authorization ------------------------------------------------------------------

    record Pending(String clientId, String redirectUri, String challenge, Instant expires) {
    }

    /** What the consent screen shows, once the request checks out. */
    public record Request(ClaudeClient client, String redirectUri, String challenge, String state) {
        public String redirectHost() {
            return URI.create(redirectUri).getHost();
        }
    }

    /**
     * Checks an authorization request. A bad client or redirect address is refused
     * outright (sending anything to an unchecked address is the classic leak); the
     * rest can be reported back to the app.
     */
    @Transactional(readOnly = true)
    public Request check(String responseType, String clientId, String redirectUri, String challenge, String method,
            String state) {
        ClaudeClient client = clientId == null ? null : clients.findById(clientId).orElse(null);
        if (client == null) {
            throw new Refused("invalid_client", "This app isn't registered. Remove the connector in Claude and add it again.");
        }
        if (redirectUri == null || !client.getRedirectUris().contains(redirectUri)) {
            throw new Refused("invalid_request", "That redirect address isn't the one this app registered.");
        }
        if (!"code".equals(responseType)) {
            throw new Refused("unsupported_response_type", "Only response_type=code is supported.", true);
        }
        if (challenge == null || challenge.length() < 43 || challenge.length() > 128 || !"S256".equals(method)) {
            throw new Refused("invalid_request", "PKCE with S256 is required.", true);
        }
        return new Request(client, redirectUri, challenge, state);
    }

    /** The code that goes back to the app after you click Allow. */
    public String approve(Request r) {
        String code = token(32);
        Instant now = Instant.now();
        codes.values().removeIf(p -> p.expires().isBefore(now));
        codes.put(code, new Pending(r.client().getClientId(), r.redirectUri(), r.challenge(), now.plus(CODE_LIFE)));
        return code;
    }

    // --- Tokens -------------------------------------------------------------------------

    public record Tokens(String accessToken, String refreshToken, long expiresIn) {
    }

    @Transactional
    public Tokens exchange(String code, String clientId, String redirectUri, String verifier) {
        Pending p = code == null ? null : codes.remove(code);
        if (p == null || p.expires().isBefore(Instant.now())) {
            throw new Refused("invalid_grant", "The code is unknown, used or expired.");
        }
        if (!p.clientId().equals(clientId) || !p.redirectUri().equals(redirectUri)) {
            throw new Refused("invalid_grant", "The code was issued to another app or address.");
        }
        if (verifier == null || !MessageDigest.isEqual(p.challenge().getBytes(StandardCharsets.US_ASCII),
                s256(verifier).getBytes(StandardCharsets.US_ASCII))) {
            throw new Refused("invalid_grant", "The code verifier doesn't match.");
        }
        ClaudeClient client = clients.findById(clientId)
                .orElseThrow(() -> new Refused("invalid_client", "This app isn't registered."));
        // Connecting the same app again replaces its old access.
        grants.deleteAll(grants.findByClientId(clientId));
        ClaudeGrant g = new ClaudeGrant(clientId, client.getName());
        return issue(g);
    }

    @Transactional
    public Tokens refresh(String refreshToken, String clientId) {
        ClaudeGrant g = refreshToken == null ? null : grants.findByRefreshHash(hash(refreshToken)).orElse(null);
        if (g == null || g.getRefreshExpiresAt().isBefore(Instant.now()) || !g.getClientId().equals(clientId)) {
            throw new Refused("invalid_grant", "The refresh token is unknown or expired. Connect again.");
        }
        return issue(g);
    }

    /** New tokens each time; the old refresh token stops working (OAuth 2.1 rotation for public clients). */
    private Tokens issue(ClaudeGrant g) {
        String access = "fa_" + token(32);
        String refresh = "fr_" + token(32);
        Instant now = Instant.now();
        g.setTokens(hash(access), now.plus(ACCESS_LIFE), hash(refresh), now.plus(REFRESH_LIFE));
        grants.save(g);
        return new Tokens(access, refresh, ACCESS_LIFE.toSeconds());
    }

    /** The grant behind an "Authorization: Bearer" header, if it's current. Notes when it was used. */
    @Transactional
    public Optional<ClaudeGrant> authorize(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        Optional<ClaudeGrant> found = grants.findByAccessHash(hash(header.substring(7).trim()))
                .filter(g -> g.getAccessExpiresAt().isAfter(Instant.now()));
        found.ifPresent(g -> {
            Instant now = Instant.now();
            if (g.getLastUsedAt() == null || g.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                g.setLastUsedAt(now);
            }
        });
        return found;
    }

    // --- Connected apps -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ClaudeGrant> connected() {
        Instant now = Instant.now();
        return grants.findAllByOrderByCreatedAtDesc().stream().filter(g -> g.getRefreshExpiresAt().isAfter(now)).toList();
    }

    @Transactional
    public void disconnect(Long grantId) {
        grants.deleteById(grantId);
    }

    // --- Helpers ------------------------------------------------------------------------

    private String token(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String s256(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
    }

    static String hash(String token) {
        return HexFormat.of().formatHex(digest(token));
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
