package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Deleting the categories the app files things into, and what happens to their jobs. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/category-merge-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class CategoryMergeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void jobsMoveWhereTheyStillWork() throws Exception {
        long checking = call(post("/api/accounts"), "{\"name\":\"Checking\",\"type\":\"CHECKING\",\"balanceCents\":0}")
                .get("id").asLong();

        // Leftovers can't move into a category you use: Shopping's purchases would look unsorted.
        long uncategorized = id("Uncategorized");
        mvc.perform(with(delete("/api/categories/" + uncategorized + "?moveTo=" + id("Shopping"))))
                .andExpect(status().isBadRequest());
        long unsorted = call(post("/api/categories"), "{\"name\":\"Unsorted\",\"kind\":\"EXPENSE\"}").get("id").asLong();
        mvc.perform(with(delete("/api/categories/" + uncategorized + "?moveTo=" + unsorted))).andExpect(status().isNoContent());
        JsonNode venmo = txn(checking, -2500, "VENMO PAYMENT 1034567");
        assertThat(venmo.get("categoryId").asLong()).isEqualTo(unsorted);
        assertThat(venmo.get("needsCategory").asBoolean()).isTrue();

        // Pay moved to a category that didn't count as income now does.
        long salary = call(post("/api/categories"), "{\"name\":\"Salary\",\"kind\":\"INCOME\",\"earnedIncome\":false}")
                .get("id").asLong();
        mvc.perform(with(delete("/api/categories/" + id("Paycheck") + "?moveTo=" + salary))).andExpect(status().isNoContent());
        JsonNode salaryNow = category(salary);
        assertThat(salaryNow.get("earnedIncome").asBoolean()).isTrue();
        assertThat(salaryNow.get("roles").toString()).contains("PAYCHECK");
        assertThat(txn(checking, 230000, "ACME PAYROLL").get("categoryId").asLong()).isEqualTo(salary);

        // Subscriptions can simply go; its charges are sorted again.
        mvc.perform(with(delete("/api/categories/" + id("Subscriptions")))).andExpect(status().isNoContent());

        // A spending target doesn't follow its transactions into savings.
        long coffee = call(post("/api/categories"), "{\"name\":\"Coffee\",\"kind\":\"EXPENSE\"}").get("id").asLong();
        call(put("/api/targets/" + coffee), "{\"monthlyLimitCents\":5000}");
        long savings = id("Savings");
        mvc.perform(with(delete("/api/categories/" + coffee + "?moveTo=" + savings))).andExpect(status().isNoContent());
        for (JsonNode t : call(get("/api/targets"), null).get("spending")) {
            assertThat(t.get("categoryId").asLong()).isNotEqualTo(savings);
        }
    }

    private JsonNode txn(long accountId, long cents, String description) throws Exception {
        return call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":%d,\"description\":\"%s\"}", accountId, LocalDate.now(),
                cents, description));
    }

    private JsonNode category(long id) throws Exception {
        for (JsonNode c : call(get("/api/categories"), null)) {
            if (c.get("id").asLong() == id) {
                return c;
            }
        }
        throw new AssertionError("no category " + id);
    }

    private long id(String name) throws Exception {
        for (JsonNode c : call(get("/api/categories"), null)) {
            if (name.equals(c.get("name").asText())) {
                return c.get("id").asLong();
            }
        }
        throw new AssertionError("no category " + name);
    }

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder request) {
        return request.with(user("me")).with(csrf());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        with(request);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String out = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }
}
