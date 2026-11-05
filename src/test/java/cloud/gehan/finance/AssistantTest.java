package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import cloud.gehan.finance.service.assistant.AssistantTools;

/**
 * The assistant against a stand-in for Anthropic's API: the question goes out with
 * the tools, a tool call comes back and is answered from the database, and nothing
 * it can read gives away a balance.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/assistant-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class AssistantTest {

    /** What the stand-in received, and how it answers the next request. */
    static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    static volatile Function<JsonNode, String> answer = r -> "{}";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final HttpServer anthropic;

    static {
        // A fresh database each run: the counts below start from nothing.
        for (String suffix : List.of("", "-wal", "-shm")) {
            new java.io.File("target/test-data/assistant-test.db" + suffix).delete();
        }
        try {
            anthropic = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        anthropic.createContext("/v1/models/", ex -> reply(ex, 200,
                "{\"type\":\"model\",\"id\":\"claude-opus-5-5\",\"display_name\":\"Claude Opus 5.5\",\"created_at\":\"2026-01-01T00:00:00Z\"}"));
        anthropic.createContext("/v1/messages", ex -> {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            requests.add(body);
            reply(ex, 200, answer.apply(body));
        });
        anthropic.start();
    }

    @DynamicPropertySource
    static void anthropicAddress(DynamicPropertyRegistry registry) {
        registry.add("app.assistant-base-url", () -> "http://127.0.0.1:" + anthropic.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        anthropic.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AssistantTools tools;

    @Test
    void looksThingsUpAndAnswers() throws Exception {
        long checking = call(post("/api/accounts"), "{\"name\":\"Everyday\",\"type\":\"CHECKING\",\"balanceCents\":1234567}")
                .get("id").asLong();
        LocalDate today = LocalDate.now();
        txn(checking, today, -650, "BLUE BOTTLE COFFEE");
        txn(checking, today.minusDays(1), -550, "BLUE BOTTLE COFFEE");

        call(put("/api/assistant/key"), "{\"key\":\"sk-ant-test-key\"}");
        assertThat(call(get("/api/assistant"), null).get("configured").asBoolean()).isTrue();

        // First Claude asks for the coffee, then answers from what came back.
        requests.clear();
        answer = r -> r.get("messages").size() == 1
                ? message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-1\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"find_transactions\",\"input\":{\"query\":\"coffee\"}}]",
                        "tool_use")
                : message("[{\"type\":\"text\",\"text\":\"You spent $12 on coffee.\"}]", "end_turn");
        JsonNode first = call(post("/api/assistant/ask"), "{\"question\":\"How much on coffee?\"}");
        assertThat(first.get("text").asText()).isEqualTo("You spent $12 on coffee.");
        assertThat(first.get("looked").get(0).asText()).isEqualTo("transactions");

        JsonNode sent = requests.get(0);
        assertThat(sent.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(sent.get("tools")).hasSize(tools.definitions().size());
        assertThat(sent.get("cache_control").get("type").asText()).isEqualTo("ephemeral");
        assertThat(sent.get("fallbacks").asText()).isEqualTo("default");
        assertThat(sent.get("messages").get(0).toString()).contains("How much on coffee?").contains("Today is");

        // The tool result went back as one user message answering toolu_1, with both coffees in it.
        JsonNode second = requests.get(1);
        JsonNode result = second.get("messages").get(2).get("content").get(0);
        assertThat(result.get("type").asText()).isEqualTo("tool_result");
        assertThat(result.get("tool_use_id").asText()).isEqualTo("toolu_1");
        JsonNode found = json.readTree(result.get("content").isTextual() ? result.get("content").asText()
                : result.get("content").get(0).get("text").asText());
        assertThat(found.get("matches").asInt()).isEqualTo(2);
        assertThat(found.get("money_out").decimalValue()).isEqualByComparingTo("12.00");

        // A follow-up carries the whole conversation, thinking block untouched.
        answer = r -> message("[{\"type\":\"text\",\"text\":\"Two visits.\"}]", "end_turn");
        String id = first.get("conversationId").asText();
        call(post("/api/assistant/ask"), "{\"conversationId\":\"" + id + "\",\"question\":\"How many times?\"}");
        JsonNode third = requests.get(2);
        assertThat(third.get("messages")).hasSize(5);
        assertThat(third.get("messages").get(1).toString()).contains("sig-1");
        assertThat(call(get("/api/assistant/chats/" + id), null).get("turns")).hasSize(4);

        // A failed answer leaves the conversation as it was.
        answer = r -> message("[]", "refusal");
        mvc.perform(post("/api/assistant/ask").with(user("me")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + id + "\",\"question\":\"Something else\"}"))
                .andExpect(status().isBadGateway());
        answer = r -> message("[{\"type\":\"text\",\"text\":\"Ok.\"}]", "end_turn");
        call(post("/api/assistant/ask"), "{\"conversationId\":\"" + id + "\",\"question\":\"Still there?\"}");
        assertThat(requests.get(requests.size() - 1).get("messages")).hasSize(7);

        assertThat(call(get("/api/assistant"), null).get("questions").asInt()).isEqualTo(4);
    }

    @Test
    void noToolGivesAwayABalance() throws Exception {
        long savings = call(post("/api/accounts"), "{\"name\":\"Rainy day\",\"type\":\"SAVINGS\",\"balanceCents\":9876543}")
                .get("id").asLong();
        long checking = call(post("/api/accounts"), "{\"name\":\"Spending\",\"type\":\"CHECKING\",\"balanceCents\":4567891}")
                .get("id").asLong();
        LocalDate today = LocalDate.now();
        txn(checking, today, 250000, "ACME PAYROLL");
        txn(checking, today, -2500, "CORNER DELI");
        long couch = txn(checking, today, -90000, "IKEA").get("id").asLong();
        call(patch("/api/transactions/" + couch), "{\"oneOff\":true}");
        call(post("/api/goals"), "{\"name\":\"Emergency fund\",\"type\":\"BALANCE\",\"targetCents\":10000000,\"accountId\":"
                + savings + "}");

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
        for (String secret : List.of("98765", "45678", "100000", "balance")) {
            assertThat(all).as(secret).doesNotContainIgnoringCase(secret);
        }
        // An unknown category gets a useful message back, not a crash.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> tools.run("find_transactions", json.readTree("{\"category\":\"Nope\"}"))))
                .isInstanceOf(AssistantTools.BadInput.class).hasMessageContaining("Categories:");
    }

    static String message(String content, String stop) {
        return "{\"id\":\"msg_" + requests.size() + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":100,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
    }

    private JsonNode txn(long accountId, LocalDate day, long cents, String description) throws Exception {
        return call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":%d,\"description\":\"%s\"}", accountId, day, cents, description));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(user("me")).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String out = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }
}
