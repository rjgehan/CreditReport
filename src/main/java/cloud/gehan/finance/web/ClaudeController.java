package cloud.gehan.finance.web;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.service.assistant.ClaudeOAuth;
import jakarta.servlet.http.HttpServletRequest;

/** The signed-in Claude screen: the address to give Claude, and the apps you've let in. */
@RestController
@RequestMapping("/api/claude")
public class ClaudeController {

    private final ClaudeOAuth oauth;
    private final AppProperties props;

    public ClaudeController(ClaudeOAuth oauth, AppProperties props) {
        this.oauth = oauth;
        this.props = props;
    }

    public record Connection(Long id, String name, Instant connectedAt, Instant lastUsedAt) {
    }

    /**
     * mcpUrl: the MCP address as seen from where you opened the app (the tailnet);
     * publicUrl: the one for the claude.ai connector, if APP_PUBLIC_URL is set.
     */
    @GetMapping
    public Map<String, Object> status(HttpServletRequest request) {
        List<Connection> apps = oauth.connected().stream()
                .map(g -> new Connection(g.getId(), g.getClientName(), g.getCreatedAt(), g.getLastUsedAt())).toList();
        Map<String, Object> out = new HashMap<>();
        out.put("mcpUrl", OAuthController.origin(request) + "/mcp");
        String pub = props.publicUrl();
        out.put("publicUrl", pub == null || pub.isBlank() ? null : pub.replaceAll("/+$", "") + "/mcp");
        out.put("connections", apps);
        return out;
    }

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long id) {
        oauth.disconnect(id);
    }
}
