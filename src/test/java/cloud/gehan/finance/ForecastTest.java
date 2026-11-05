package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** This month looking ahead: pay still coming, bills still due, savings, and what's free. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/forecast-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class ForecastTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void freeIsPayLessSpendingBillsAndSavings() throws Exception {
        long checking = call(post("/api/accounts"), "{\"name\":\"Checking\",\"type\":\"CHECKING\",\"balanceCents\":0}")
                .get("id").asLong();
        LocalDate today = LocalDate.now();
        YearMonth now = YearMonth.from(today);
        // Three full months of pay, rent, a subscription and everyday spending, then this month so far.
        for (int back = 3; back >= 0; back--) {
            YearMonth m = now.minusMonths(back);
            for (LocalDate day : new LocalDate[] { m.atDay(15), m.atEndOfMonth() }) {
                if (!day.isAfter(today)) {
                    txn(checking, day, 230000, "ACME PAYROLL");
                }
            }
            txn(checking, m.atDay(1), -134500, "AVALON RENT");
            if (back > 0 || !m.atDay(9).isAfter(today)) {
                txn(checking, m.atDay(9), -1549, "NETFLIX.COM");
            }
            for (int d = 2; d <= m.lengthOfMonth(); d += 4) {
                if (back > 0 || !m.atDay(d).isAfter(today)) {
                    txn(checking, m.atDay(d), -(3000 + d * 11), "JOES TAVERN RESTAURANT");
                }
            }
        }
        call(post("/api/paychecks/track"), "{\"pattern\":\"ACME PAYROLL\"}");
        call(put("/api/settings"), "{\"savingsTargetCents\":50000}");
        // The furniture: a one-off, outside all of this.
        long couch = txn(checking, today, -180000, "IKEA ELIZABETH").get("id").asLong();
        call(patch("/api/transactions/" + couch), "{\"oneOff\":true}");

        JsonNode f = call(get("/api/forecast"), null);
        long coming = 0;
        for (JsonNode c : f.get("incomeComing")) {
            coming += c.get("cents").asLong();
        }
        assertThat(f.get("incomeCents").asLong()).isEqualTo(f.get("receivedCents").asLong() + coming);
        long room = f.get("incomeCents").asLong() - f.get("spentCents").asLong() - f.get("billsComingCents").asLong();
        assertThat(f.get("roomCents").asLong()).isEqualTo(room);
        assertThat(f.get("freeCents").asLong()).isEqualTo(Math.max(0, room - 50000));
        assertThat(f.get("oneOffCents").asLong()).isEqualTo(180000);
        assertThat(f.get("history").asBoolean()).isTrue();
        assertThat(f.get("status").asText()).isIn("ok", "tight", "under", "short");
        assertThat(f.get("endCents").asLong()).isEqualTo(f.get("freeCents").asLong() - f.get("usualRestCents").asLong());
        // Twice-a-month pay: whatever paydays are left this month are on their way.
        for (JsonNode c : f.get("incomeComing")) {
            assertThat(LocalDate.parse(c.get("day").asText())).isBetween(today, now.atEndOfMonth());
        }
        // Rent is paid; next month's isn't due yet.
        for (JsonNode c : f.get("billsComing")) {
            assertThat(LocalDate.parse(c.get("day").asText())).isBetween(today, now.atEndOfMonth());
        }
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
