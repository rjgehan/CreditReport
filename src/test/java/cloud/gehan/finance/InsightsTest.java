package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.service.insights.ClaudeCode;

/**
 * Claude's morning run against a stand-in for Claude Code: what it's asked (never a
 * balance, no tools), and what happens to its answer.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/insights-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class InsightsTest {

    static {
        for (String suffix : List.of("", "-wal", "-shm")) {
            new java.io.File("target/test-data/insights-test.db" + suffix).delete();
        }
    }

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<String> prompts = new CopyOnWriteArrayList<>();
    static final List<List<String>> commands = new CopyOnWriteArrayList<>();

    /** Answers like Claude Code's print mode, picking real ids out of the data it was given. */
    @TestConfiguration
    static class FakeClaude {
        @Bean
        @Primary
        ClaudeCode.Cli fakeCli() {
            return (command, input, env, dir, timeout) -> {
                prompts.add(input);
                commands.add(command);
                assertThat(env).containsKey("CLAUDE_CODE_OAUTH_TOKEN").doesNotContainKey("APP_PASSWORD");
                try {
                    String answer;
                    if (input.contains("Write today's notes")) {
                        JsonNode data = MAPPER.readTree(input.substring(input.indexOf("Data:\n") + 6));
                        long leftover = -1;
                        for (JsonNode t : data.get("to_file")) {
                            if (t.get("description").asText().contains("CORNER")) {
                                leftover = t.get("id").asLong();
                            }
                        }
                        long recent = data.at("/recent_transactions/transactions/0/id").asLong();
                        long bed = -1;
                        for (JsonNode t : data.at("/spending_history/transactions")) {
                            if (t.get("description").asText().contains("MATTRESS")) {
                                bed = t.get("id").asLong();
                            }
                        }
                        answer = "{\"status\":\"watch\",\"headline\":\"Watch: bars are up.\",\"notes\":[\"Bars $90, about double usual.\"],"
                                + "\"odd_charges\":[{\"transaction_id\":" + recent + ",\"reason\":\"Charged twice\"}],"
                                + "\"file\":[{\"transaction_id\":" + leftover + ",\"category\":\"Groceries\"}],"
                                + "\"rules\":[{\"pattern\":\"corner deli\",\"category\":\"Groceries\",\"reason\":\"Shows up weekly\"}],"
                                + "\"one_offs\":[{\"transaction_id\":" + bed + ",\"reason\":\"A mattress, bought every few years\"}],"
                                + "\"week_recap\":[\"A quiet week.\"]}";
                    } else if (input.contains("Write the review")) {
                        answer = "{\"review\":[\"Spending was steady.\"],\"targets\":[{\"category\":\"Groceries\",\"monthly_limit\":400,\"reason\":\"You spend about $380\"}]}";
                    } else if (input.contains("They ask")) {
                        answer = "{\"answer\":\"yes\",\"text\":\"Yes: $500 is free this month.\"}";
                    } else {
                        answer = "{\"ok\":true}";
                    }
                    return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":"
                            + MAPPER.writeValueAsString(answer) + ",\"structured_output\":" + answer + "}";
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void theMorningRunWritesFilesFlagsAndSuggests() throws Exception {
        long checking = call(post("/api/accounts"), "{\"name\":\"Spending\",\"type\":\"CHECKING\",\"balanceCents\":4567891}")
                .get("id").asLong();
        LocalDate old = LocalDate.now().minusDays(10);
        long deli = call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":-2500,\"description\":\"CORNER DELI 42\"}", checking, old))
                .get("id").asLong();
        // Typed in counts as checked off; a bank's leftover isn't.
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/transactions/" + deli),
                "{\"reviewed\":false}");
        call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":-9000,\"description\":\"THE BAR\"}", checking, old));
        long mattress = call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":-120000,\"description\":\"MATTRESS FIRM\"}", checking, old))
                .get("id").asLong();
        // Filed as shopping, so it counts in the averages until it's left out.
        long shopping = call(get("/api/categories"), null).findParents("name").stream()
                .filter(c -> c.get("name").asText().equals("Shopping")).findFirst().orElseThrow().get("id").asLong();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/transactions/" + mattress),
                "{\"categoryId\":" + shopping + "}");

        assertThat(call(get("/api/insights"), null).get("configured").asBoolean()).isFalse();
        call(put("/api/insights/token"), "{\"token\":\"sk-ant-oat01-test\"}");
        JsonNode s = call(post("/api/insights/refresh"), null);

        assertThat(s.at("/take/status").asText()).isEqualTo("watch");
        assertThat(s.at("/take/headline").asText()).isEqualTo("Watch: bars are up.");
        assertThat(s.at("/take/lines/0").asText()).contains("Bars $90");
        List<String> kinds = MAPPER.convertValue(s.get("items").findValuesAsText("kind"), List.class);
        assertThat(kinds).contains("CHARGE", "RULE", "TARGET", "ONE_OFF");

        // Leaving the mattress out: it's then marked one-off and the suggestion goes away.
        JsonNode bedItem = null;
        for (JsonNode i : s.get("items")) {
            if (i.get("kind").asText().equals("ONE_OFF")) {
                bedItem = i;
            }
        }
        assertThat(bedItem.get("transactionId").asLong()).isEqualTo(mattress);
        assertThat(bedItem.get("reason").asText()).contains("mattress");
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/transactions/" + mattress),
                "{\"oneOff\":true}");
        assertThat(call(get("/api/insights"), null).get("items").findValuesAsText("kind")).doesNotContain("ONE_OFF");
        // Asked again tomorrow, the same purchase isn't suggested twice.
        call(post("/api/insights/refresh"), null);
        assertThat(call(get("/api/insights"), null).get("items").findValuesAsText("kind")).doesNotContain("ONE_OFF");
        assertThat(s.toString()).contains("CORNER DELI").contains("Shows up weekly");

        // The leftover was filed, as Claude's pick.
        JsonNode filed = call(get("/api/transactions?q=corner"), null).at("/items/0");
        String daily = prompts.stream().filter(x -> x.contains("Write today's notes")).findFirst().orElse("");
        int at = Math.max(0, daily.indexOf("\"to_file\""));
        assertThat(filed.get("categoryName").asText()).as(filed + " / " + daily.substring(at, Math.min(daily.length(), at + 600)))
                .isEqualTo("Groceries");
        assertThat(filed.get("categorySource").asText()).isEqualTo("AI");

        // Last month's review shows in History.
        String last = java.time.YearMonth.now().minusMonths(1).toString();
        assertThat(call(get("/api/insights/month?month=" + last), null).at("/note/lines/0").asText()).isEqualTo("Spending was steady.");

        // What Claude was asked: no balance in it, and no tools to use.
        for (String p : prompts) {
            assertThat(p).doesNotContain("45678").doesNotContainIgnoringCase("balance\"");
        }
        assertThat(commands.get(commands.size() - 1)).containsSubsequence("--tools", "");

        JsonNode a = call(post("/api/insights/afford"), "{\"question\":\"A $200 concert?\"}");
        assertThat(a.get("answer").asText()).isEqualTo("yes");

        // Approving and dismissing take items off the list.
        JsonNode open = call(get("/api/insights"), null);
        long id = open.at("/items/0/id").asLong();
        JsonNode after = call(post("/api/insights/items/" + id + "/dismissed"), null);
        assertThat(after.get("items").size()).isEqualTo(open.get("items").size() - 1);
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
