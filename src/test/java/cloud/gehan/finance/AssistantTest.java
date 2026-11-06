package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.service.assistant.AssistantTools;

/**
 * Claude connecting the way it does to any connector (discover, register, sign in
 * with PKCE, get tokens), then reading over MCP; and nothing it reads gives away a
 * balance.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/assistant-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class AssistantTest {

    static {
        // A fresh database each run: the counts below start from nothing.
        for (String suffix : List.of("", "-wal", "-shm")) {
            new java.io.File("target/test-data/assistant-test.db" + suffix).delete();
        }
    }

    static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AssistantTools tools;

    @Test
    void claudeSignsInAndReads() throws Exception {
        long checking = call(post("/api/accounts").with(user("me")).with(csrf()),
                "{\"name\":\"Everyday\",\"type\":\"CHECKING\",\"balanceCents\":1234567}").get("id").asLong();
        LocalDate today = LocalDate.now();
        txn(checking, today, -650, "BLUE BOTTLE COFFEE");
        txn(checking, today.minusDays(1), -550, "BLUE BOTTLE COFFEE");

        // Without a token, /mcp says where to sign in.
        MockHttpServletResponse denied = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .content(rpc(1, "initialize", "{}"))).andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(denied.getHeader("WWW-Authenticate"))
                .contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");

        JsonNode resource = call(get("/.well-known/oauth-protected-resource/mcp"), null);
        assertThat(resource.get("resource").asText()).isEqualTo("http://localhost/mcp");
        JsonNode server = call(get("/.well-known/oauth-authorization-server"), null);
        assertThat(server.get("code_challenge_methods_supported").toString()).contains("S256");
        assertThat(server.get("authorization_endpoint").asText()).isEqualTo("http://localhost/oauth2/authorize");

        // Registering: only Claude's own addresses.
        mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"client_name\":\"x\",\"redirect_uris\":[\"https://evil.example/cb\"]}"))
                .andExpect(status().isBadRequest());
        String clientId = call(post("/oauth2/register"),
                "{\"client_name\":\"Claude <b>\",\"redirect_uris\":[\"" + CALLBACK + "\"]}").get("client_id").asText();

        String verifier = "a-verifier-that-is-long-enough-for-pkce-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        MockHttpServletRequestBuilder authorize = get("/oauth2/authorize").param("response_type", "code")
                .param("client_id", clientId).param("redirect_uri", CALLBACK).param("code_challenge", challenge)
                .param("code_challenge_method", "S256").param("state", "st8");

        // Signed out: off to sign in, and back here after.
        String login = mvc.perform(authorize).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(login).startsWith("/login.html?next=%2Foauth2%2Fauthorize%3F");

        // Signed in: the consent screen, with the app's name escaped.
        String page = mvc.perform(authorize.with(user("me"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(page).contains("Connect Claude?").contains("Claude &lt;b&gt;").contains("claude.ai");

        // A redirect address it didn't register is never used.
        mvc.perform(get("/oauth2/authorize").with(user("me")).param("response_type", "code").param("client_id", clientId)
                .param("redirect_uri", "https://claude.ai/elsewhere").param("code_challenge", challenge)
                .param("code_challenge_method", "S256")).andExpect(status().isOk());

        String back = mvc.perform(post("/oauth2/authorize").with(user("me")).with(csrf())
                .param("response_type", "code").param("client_id", clientId).param("redirect_uri", CALLBACK)
                .param("code_challenge", challenge).param("code_challenge_method", "S256").param("state", "st8")
                .param("decision", "allow")).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(back).startsWith(CALLBACK + "?code=").contains("state=st8");
        String code = UriComponentsBuilder.fromUri(URI.create(back)).build().getQueryParams().getFirst("code");

        // The code needs the matching verifier, and works once.
        mvc.perform(token("authorization_code", "code", code, "client_id", clientId, "redirect_uri", CALLBACK,
                "code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier-000")).andExpect(status().isBadRequest());
        String back2 = mvc.perform(post("/oauth2/authorize").with(user("me")).with(csrf())
                .param("response_type", "code").param("client_id", clientId).param("redirect_uri", CALLBACK)
                .param("code_challenge", challenge).param("code_challenge_method", "S256")
                .param("decision", "allow")).andReturn().getResponse().getHeader("Location");
        String code2 = UriComponentsBuilder.fromUri(URI.create(back2)).build().getQueryParams().getFirst("code");
        JsonNode tokens = body(mvc.perform(token("authorization_code", "code", code2, "client_id", clientId,
                "redirect_uri", CALLBACK, "code_verifier", verifier)).andExpect(status().isOk()).andReturn().getResponse());
        String access = tokens.get("access_token").asText();
        mvc.perform(token("authorization_code", "code", code2, "client_id", clientId, "redirect_uri", CALLBACK,
                "code_verifier", verifier)).andExpect(status().isBadRequest());

        // Now MCP answers.
        JsonNode init = mcp(access, rpc(1, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}"));
        assertThat(init.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.at("/result/instructions").asText()).contains("can't see account balances");
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());
        JsonNode list = mcp(access, rpc(2, "tools/list", "{}"));
        assertThat(list.at("/result/tools")).hasSize(tools.definitions().size());
        assertThat(list.at("/result/tools/0/annotations/readOnlyHint").asBoolean()).isTrue();

        JsonNode found = mcp(access, rpc(3, "tools/call", "{\"name\":\"find_transactions\",\"arguments\":{\"query\":\"coffee\"}}"));
        assertThat(found.at("/result/isError").asBoolean()).isFalse();
        JsonNode rows = json.readTree(found.at("/result/content/0/text").asText());
        assertThat(rows.get("matches").asInt()).isEqualTo(2);
        assertThat(rows.get("money_out").decimalValue()).isEqualByComparingTo("12.00");

        JsonNode bad = mcp(access, rpc(4, "tools/call", "{\"name\":\"find_transactions\",\"arguments\":{\"category\":\"Nope\"}}"));
        assertThat(bad.at("/result/isError").asBoolean()).isTrue();
        assertThat(mcp(access, rpc(5, "resources/list", "{}")).at("/error/code").asInt()).isEqualTo(-32601);

        // A web page can't use it even with a token.
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + access).header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).content(rpc(6, "ping", "{}"))).andExpect(status().isForbidden());

        // Refreshing swaps both tokens; the old refresh token is spent.
        JsonNode renewed = body(mvc.perform(token("refresh_token", "refresh_token", tokens.get("refresh_token").asText(),
                "client_id", clientId)).andExpect(status().isOk()).andReturn().getResponse());
        mvc.perform(token("refresh_token", "refresh_token", tokens.get("refresh_token").asText(), "client_id", clientId))
                .andExpect(status().isBadRequest());
        String fresh = renewed.get("access_token").asText();
        assertThat(mcp(fresh, rpc(7, "ping", "{}")).has("result")).isTrue();

        // It shows under Accounts, More, Claude, and disconnecting cuts it off.
        JsonNode status = call(get("/api/claude").with(user("me")), null);
        assertThat(status.get("connections")).hasSize(1);
        long id = status.at("/connections/0/id").asLong();
        mvc.perform(delete("/api/claude/connections/" + id).with(user("me")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + fresh).contentType(MediaType.APPLICATION_JSON)
                .content(rpc(8, "ping", "{}"))).andExpect(status().isUnauthorized());
    }

    @Test
    void onTheTunnelOnlyClaudesPartsAnswer() throws Exception {
        org.springframework.test.web.servlet.request.RequestPostProcessor tunnel = r -> {
            r.setServerName("finance.example.com");
            r.setScheme("https");
            r.setServerPort(443);
            return r;
        };
        mvc.perform(get("/api/accounts").with(tunnel).with(user("me"))).andExpect(status().isNotFound());
        mvc.perform(get("/").with(tunnel)).andExpect(status().isNotFound());
        mvc.perform(get("/index.html").with(tunnel)).andExpect(status().isNotFound());
        JsonNode server = body(mvc.perform(get("/.well-known/oauth-authorization-server").with(tunnel))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(server.get("issuer").asText()).isEqualTo("https://finance.example.com");
        assertThat(server.get("authorization_endpoint").asText()).isEqualTo("https://finance.example.com/oauth2/authorize");
        mvc.perform(post("/mcp").with(tunnel).contentType(MediaType.APPLICATION_JSON).content(rpc(1, "ping", "{}")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/login.html").with(tunnel)).andExpect(status().isOk());
        // The Tailscale address has everything.
        mvc.perform(get("/api/claude").with(r -> { r.setServerName("homeserver.example.ts.net"); return r; })
                .with(user("me"))).andExpect(status().isOk());
    }

    @Test
    void noToolGivesAwayABalance() throws Exception {
        long savings = call(post("/api/accounts").with(user("me")).with(csrf()),
                "{\"name\":\"Rainy day\",\"type\":\"SAVINGS\",\"balanceCents\":9876543}").get("id").asLong();
        long checking = call(post("/api/accounts").with(user("me")).with(csrf()),
                "{\"name\":\"Spending\",\"type\":\"CHECKING\",\"balanceCents\":4567891}").get("id").asLong();
        LocalDate today = LocalDate.now();
        txn(checking, today, 250000, "ACME PAYROLL");
        txn(checking, today, -2500, "CORNER DELI");
        long couch = txn(checking, today, -90000, "IKEA").get("id").asLong();
        call(patch("/api/transactions/" + couch).with(user("me")).with(csrf()), "{\"oneOff\":true}");
        call(post("/api/goals").with(user("me")).with(csrf()),
                "{\"name\":\"Emergency fund\",\"type\":\"BALANCE\",\"targetCents\":10000000,\"accountId\":" + savings + "}");

        String month = YearMonth.now().toString();
        String all = String.join("\n",
                tools.run("find_transactions", json.readTree("{}")),
                tools.run("monthly_summary", json.readTree("{\"from_month\":\"" + month + "\"}")),
                tools.run("this_month", json.readTree("{}")),
                tools.run("spending_targets_and_goals", json.readTree("{}")),
                tools.run("monthly_expenses", json.readTree("{}")),
                tools.run("rent_and_loans", json.readTree("{}")),
                tools.run("paychecks", json.readTree("{}")),
                tools.run("list_categories_and_accounts", json.readTree("{}")));
        assertThat(all).contains("CORNER DELI").contains("Emergency fund").contains("Rainy day");
        for (String secret : List.of("98765", "45678", "12345", "100000", "balance")) {
            assertThat(all).as(secret).doesNotContainIgnoringCase(secret);
        }
    }

    private MockHttpServletRequestBuilder token(String grant, String... pairs) {
        MockHttpServletRequestBuilder b = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", grant);
        for (int i = 0; i < pairs.length; i += 2) {
            b.param(pairs[i], pairs[i + 1]);
        }
        return b;
    }

    private JsonNode mcp(String access, String message) throws Exception {
        return body(mvc.perform(post("/mcp").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content(message)).andExpect(status().isOk()).andReturn().getResponse());
    }

    private static String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode txn(long accountId, LocalDate day, long cents, String description) throws Exception {
        return call(post("/api/transactions").with(user("me")).with(csrf()), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":%d,\"description\":\"%s\"}", accountId, day, cents, description));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return body(mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse());
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        String out = response.getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }
}
