package cloud.gehan.finance.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.gehan.finance.service.assistant.AssistantTools;
import cloud.gehan.finance.service.assistant.ClaudeOAuth;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The app as an MCP server, so Claude (Claude Code, on your own subscription) can
 * read your transactions and what's worked out from them, never a balance.
 *
 * <p>Streamable HTTP, stateless: each POST carries one JSON-RPC message (or a batch)
 * and gets its answer as plain JSON. There is no event stream to open, so GET is
 * refused. Requests carry an OAuth access token from {@link ClaudeOAuth}; without
 * one, the 401 says where to sign in, which is how Claude knows to ask you.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** Newest first. A client asking for one of these gets it; anything else gets the first. */
    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    /** A JSON-RPC message for this server is a few hundred bytes; anything far bigger isn't one. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    static final String INSTRUCTIONS = """
            Finance is the user's own personal finance app, running on their home server. These tools read its \
            data: transactions, monthly totals, this month's outlook, spending targets and goals, recurring \
            charges, rent and loans, and paychecks.

            By design they can't see account balances, net worth or what investments are worth. If asked, say so \
            and point the user to the app's Accounts tab; don't work a balance out from transactions.

            The tools only read. To change something, tell the user where in the app: Activity for transactions \
            and their categories, Home for targets and goals, Accounts > More for monthly expenses, rent and loans, \
            categories and paychecks.

            How the data works:
            - Amounts are US dollars. In transactions, negative is money out and positive is money in.
            - Pay arrives after taxes and 401(k) contributions, so income is take-home.
            - Credit card payments and moves between the user's own accounts are transfers: neither income nor \
            spending.
            - A one-off is a purchase the user marked to keep out of their budget and averages (furniture after a \
            move, say). Mention one-offs when they matter, but don't count them as usual spending.
            - Rules file transactions into categories, so a few may be in the wrong one. If a total looks off because \
            of that, name the transactions and suggest re-filing them in Activity.
            - Bank history only goes back so far (history_starts in results); say so when a question reaches past it.

            Every number you give should come from a tool result, or arithmetic on them.
            """;

    private final AssistantTools tools;
    private final ClaudeOAuth oauth;
    private final ObjectMapper json;

    public McpController(AssistantTools tools, ClaudeOAuth oauth, ObjectMapper json) {
        this.tools = tools;
        this.oauth = oauth;
        this.json = json;
    }

    @RequestMapping(path = "/mcp", method = RequestMethod.POST)
    public ResponseEntity<?> post(HttpServletRequest request) throws IOException {
        // Only Claude calls this, never a web page: a browser request (which carries an
        // Origin) could be a site trying its luck through DNS rebinding.
        if (request.getHeader(HttpHeaders.ORIGIN) != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (oauth.authorize(request.getHeader(HttpHeaders.AUTHORIZATION)).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\""
                            + OAuthController.origin(request) + "/.well-known/oauth-protected-resource/mcp\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "invalid_token", "error_description", "Sign in to Finance to connect."));
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (IOException e) {
            return ok(error(null, -32700, "Not valid JSON"));
        }
        if (message == null) {
            return ok(error(null, -32600, "Empty request"));
        }
        if (message.isArray()) {
            ArrayNode replies = json.createArrayNode();
            message.forEach(m -> {
                JsonNode reply = handle(m);
                if (reply != null) {
                    replies.add(reply);
                }
            });
            return replies.isEmpty() ? ResponseEntity.accepted().build() : ok(replies);
        }
        JsonNode reply = handle(message);
        // Notifications (and replies to anything we asked, which is nothing) get no answer.
        return reply == null ? ResponseEntity.accepted().build() : ok(reply);
    }

    /** No event stream: everything comes back in the POST's own response. */
    @RequestMapping(path = "/mcp", method = { RequestMethod.GET, RequestMethod.DELETE })
    public ResponseEntity<Void> other() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private JsonNode handle(JsonNode m) {
        JsonNode id = m.get("id");
        String method = m.path("method").asText(null);
        if (id == null || id.isNull() || method == null) {
            return null;
        }
        JsonNode params = m.path("params");
        return switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, json.createObjectNode());
            case "tools/list" -> result(id, toolList());
            case "tools/call" -> call(id, params);
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "finance");
        info.put("title", "Finance");
        info.put("version", "1");
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private ObjectNode toolList() {
        ObjectNode r = json.createObjectNode();
        ArrayNode list = r.putArray("tools");
        for (AssistantTools.Definition d : tools.definitions()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", d.name());
            tool.put("description", d.description());
            tool.put("inputSchema", d.inputSchema());
            tool.put("annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));
            list.add(json.valueToTree(tool));
        }
        return r;
    }

    private ObjectNode call(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        if (tools.definitions().stream().noneMatch(d -> d.name().equals(name))) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        JsonNode arguments = params.path("arguments");
        String text;
        boolean failed = false;
        try {
            text = tools.run(name, arguments.isObject() ? arguments : json.createObjectNode());
        } catch (AssistantTools.BadInput e) {
            text = e.getMessage();
            failed = true;
        } catch (RuntimeException e) {
            log.warn("MCP tool {} failed", name, e);
            text = "That lookup failed inside the app (" + e.getClass().getSimpleName() + ").";
            failed = true;
        }
        ObjectNode r = json.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", failed);
        return result(id, r);
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
