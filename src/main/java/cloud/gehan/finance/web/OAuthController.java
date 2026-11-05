package cloud.gehan.finance.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.ClaudeClient;
import cloud.gehan.finance.service.assistant.ClaudeOAuth;
import cloud.gehan.finance.service.assistant.ClaudeOAuth.Refused;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The OAuth side of connecting Claude: the discovery documents, registration and
 * the token endpoint (called by Anthropic's servers), and the consent screen
 * (opened in your browser, behind your sign-in).
 */
@RestController
public class OAuthController {

    private final ClaudeOAuth oauth;
    private final AppProperties props;

    public OAuthController(ClaudeOAuth oauth, AppProperties props) {
        this.oauth = oauth;
        this.props = props;
    }

    /** Where this request came in: the public address for Anthropic, the tailnet one for you. */
    static String origin(HttpServletRequest request) {
        String host = request.getServerName();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        // Tailscale serve and Cloudflare both end HTTPS in front of the app.
        String scheme = local ? request.getScheme() : "https";
        int port = request.getServerPort();
        boolean defaultPort = port <= 0 || (scheme.equals("https") ? port == 443 || port == 80 : port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** Where your browser goes to approve Claude: always the address you sign in at. */
    private String signInOrigin(HttpServletRequest request) {
        String url = props.url();
        return url == null || url.isBlank() ? origin(request) : url.replaceAll("/+$", "");
    }

    // --- Discovery (RFC 9728, RFC 8414) -------------------------------------------------

    @GetMapping({ "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp" })
    public ResponseEntity<Map<String, Object>> resource(HttpServletRequest request) {
        String origin = origin(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", origin + "/mcp");
        m.put("authorization_servers", List.of(origin));
        m.put("scopes_supported", List.of(ClaudeOAuth.SCOPE));
        m.put("bearer_methods_supported", List.of("header"));
        m.put("resource_name", "Finance");
        return json(m);
    }

    @GetMapping({ "/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server/mcp" })
    public ResponseEntity<Map<String, Object>> server(HttpServletRequest request) {
        String origin = origin(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", origin);
        m.put("authorization_endpoint", signInOrigin(request) + "/oauth2/authorize");
        m.put("token_endpoint", origin + "/oauth2/token");
        m.put("registration_endpoint", origin + "/oauth2/register");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        m.put("scopes_supported", List.of(ClaudeOAuth.SCOPE));
        return json(m);
    }

    // --- Registration (RFC 7591) --------------------------------------------------------

    @PostMapping(path = "/oauth2/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode body) {
        List<String> uris = new ArrayList<>();
        body.path("redirect_uris").forEach(u -> uris.add(u.asText()));
        try {
            ClaudeClient c = oauth.register(body.path("client_name").asText(null), uris);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client_id", c.getClientId());
            m.put("client_id_issued_at", c.getCreatedAt().getEpochSecond());
            m.put("client_name", c.getName());
            m.put("redirect_uris", c.getRedirectUris());
            m.put("grant_types", List.of("authorization_code", "refresh_token"));
            m.put("response_types", List.of("code"));
            m.put("token_endpoint_auth_method", "none");
            m.put("scope", ClaudeOAuth.SCOPE);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON).body(m);
        } catch (Refused e) {
            return oauthError(e);
        }
    }

    // --- Tokens -------------------------------------------------------------------------

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> form) {
        try {
            ClaudeOAuth.Tokens t = switch (form.getOrDefault("grant_type", "")) {
                case "authorization_code" -> oauth.exchange(form.get("code"), form.get("client_id"),
                        form.get("redirect_uri"), form.get("code_verifier"));
                case "refresh_token" -> oauth.refresh(form.get("refresh_token"), form.get("client_id"));
                default -> throw new Refused("unsupported_grant_type", "Use authorization_code or refresh_token.");
            };
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("access_token", t.accessToken());
            m.put("token_type", "Bearer");
            m.put("expires_in", t.expiresIn());
            m.put("refresh_token", t.refreshToken());
            m.put("scope", ClaudeOAuth.SCOPE);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(m);
        } catch (Refused e) {
            return oauthError(e);
        }
    }

    // --- Consent ------------------------------------------------------------------------

    /**
     * Opened in your browser by Claude's "Connect". Needs your normal sign-in (on
     * Tailscale); without it you're sent to sign in and brought back here.
     */
    @GetMapping(path = "/oauth2/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(HttpServletRequest request,
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "code_challenge", required = false) String challenge,
            @RequestParam(name = "code_challenge_method", required = false) String method,
            @RequestParam(required = false) String state) {
        if (request.getUserPrincipal() == null) {
            UriComponentsBuilder here = UriComponentsBuilder.fromPath(request.getRequestURI());
            request.getParameterMap().forEach((k, v) -> here.queryParam(k, (Object[]) v));
            String back = here.encode().build().toUriString();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", "/login.html?next=" + URLEncoder.encode(back, StandardCharsets.UTF_8)).build();
        }
        ClaudeOAuth.Request r;
        try {
            r = oauth.check(responseType, clientId, redirectUri, challenge, method, state);
        } catch (Refused e) {
            return e.toApp() ? back(redirectUri, e.code(), e.getMessage(), state) : problem(e.getMessage());
        }
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        StringBuilder hidden = new StringBuilder();
        hiddenField(hidden, csrf.getParameterName(), csrf.getToken());
        hiddenField(hidden, "response_type", responseType);
        hiddenField(hidden, "client_id", clientId);
        hiddenField(hidden, "redirect_uri", redirectUri);
        hiddenField(hidden, "code_challenge", challenge);
        hiddenField(hidden, "code_challenge_method", method);
        if (state != null) {
            hiddenField(hidden, "state", state);
        }
        String form = "<form method=\"post\" action=\"/oauth2/authorize\" class=\"btn-row\" style=\"margin-top:16px\">" + hidden
                + "<button class=\"btn primary\" name=\"decision\" value=\"allow\">Allow</button>"
                + "<button class=\"btn\" name=\"decision\" value=\"deny\">Cancel</button></form>";
        String body = "<p><b>" + HtmlUtils.htmlEscape(r.client().getName()) + "</b> (returning to "
                + HtmlUtils.htmlEscape(r.redirectHost()) + ") is asking to read your finances.</p>"
                + "<p class=\"hint\">It can look at your transactions, monthly totals, this month's outlook, targets, "
                + "goals, monthly expenses, rent and loans, and paychecks. It can't see account balances, net worth or "
                + "investments, and it can't change anything. Disconnect it any time under Accounts, More, Claude.</p>";
        return page("Connect Claude?", body, form);
    }

    @PostMapping(path = "/oauth2/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decide(HttpServletRequest request,
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "code_challenge", required = false) String challenge,
            @RequestParam(name = "code_challenge_method", required = false) String method,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String decision) {
        if (request.getUserPrincipal() == null) {
            return problem("You're signed out. Go back to Claude and click Connect again.");
        }
        ClaudeOAuth.Request r;
        try {
            r = oauth.check(responseType, clientId, redirectUri, challenge, method, state);
        } catch (Refused e) {
            return e.toApp() ? back(redirectUri, e.code(), e.getMessage(), state) : problem(e.getMessage());
        }
        if (!"allow".equals(decision)) {
            return back(redirectUri, "access_denied", "You didn't allow it.", state);
        }
        UriComponentsBuilder to = UriComponentsBuilder.fromUriString(r.redirectUri()).queryParam("code", oauth.approve(r));
        if (state != null) {
            to.queryParam("state", state);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", to.encode().build().toUriString()).build();
    }

    // --- Helpers ------------------------------------------------------------------------

    private static ResponseEntity<String> back(String redirectUri, String error, String description, String state) {
        UriComponentsBuilder to = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", error).queryParam("error_description", description);
        if (state != null) {
            to.queryParam("state", state);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", to.encode().build().toUriString()).build();
    }

    private static ResponseEntity<String> problem(String message) {
        return page("Can't connect", "<p>" + HtmlUtils.htmlEscape(message) + "</p>", null);
    }

    private static void hiddenField(StringBuilder out, String name, String value) {
        out.append("<input type=\"hidden\" name=\"").append(HtmlUtils.htmlEscape(name)).append("\" value=\"")
                .append(HtmlUtils.htmlEscape(value == null ? "" : value)).append("\">");
    }

    private static ResponseEntity<String> page(String title, String body, String form) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex, nofollow\">"
                + "<title>" + HtmlUtils.htmlEscape(title) + " · Finance</title>"
                + "<link rel=\"stylesheet\" href=\"/assets/app.css\"></head><body>"
                + "<main style=\"max-width:460px;padding-top:calc(env(safe-area-inset-top) + 32px)\">"
                + "<section class=\"card\"><h1 style=\"font-size:22px;margin:0 0 8px\">" + HtmlUtils.htmlEscape(title) + "</h1>"
                + body + (form == null ? "" : form) + "</section></main></body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
                // Never inside someone else's page, where the Allow button could be clicked for you.
                .header("X-Frame-Options", "DENY").body(html);
    }

    private static ResponseEntity<Map<String, Object>> oauthError(Refused e) {
        HttpStatus status = "invalid_client".equals(e.code()) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.code(), "error_description", e.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> json(Map<String, Object> m) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(m);
    }
}
