package cloud.gehan.finance.config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The whole app answers on the Tailscale address (and localhost). Anywhere else,
 * which in practice means the Cloudflare Tunnel hostname Claude connects through,
 * only Claude's parts do: its MCP server, its sign-in and the consent page. So the
 * app and your balances never reach the internet, and nothing has to be set for
 * that to hold.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicHostFilter extends OncePerRequestFilter {

    static final List<String> OPEN = List.of("/mcp", "/oauth2/", "/.well-known/oauth-", "/login", "/assets/",
            "/icons/", "/favicon.ico", "/robots.txt", "/error");

    /** Tailscale serve's own hostname, or this machine. */
    static boolean privateHost(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.endsWith(".ts.net") || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]")
                || h.equals("::1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!privateHost(request.getServerName())) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (OPEN.stream().noneMatch(path::startsWith)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
