package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Targets filled in from three months of history, and the regular charges in it. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/planning-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class PlanningTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static boolean seeded;
    private static long checking;
    private static long card;

    /** Three full months: pay twice a month, rent, groceries, dining out, Netflix, two Apple charges, coffee. */
    private synchronized void seed() throws Exception {
        if (seeded) {
            return;
        }
        checking = call(post("/api/accounts"), "{\"name\":\"Checking\",\"type\":\"CHECKING\",\"balanceCents\":0}").get("id").asLong();
        card = call(post("/api/accounts"), "{\"name\":\"Card\",\"type\":\"CREDIT_CARD\",\"balanceCents\":0}").get("id").asLong();
        call(post("/api/accounts"), "{\"name\":\"Savings\",\"type\":\"SAVINGS\",\"balanceCents\":500000}");
        YearMonth now = YearMonth.now();
        for (int back = 3; back >= 1; back--) {
            YearMonth m = now.minusMonths(back);
            txn(checking, m.atDay(15), 230000, "ACME PAYROLL");
            txn(checking, m.atEndOfMonth(), 230000, "ACME PAYROLL");
            txn(checking, m.atDay(1), -180000, "AVALON RENT");
            txn(card, m.atDay(6), -(21000 + back * 3100), "WEGMANS 87");
            txn(card, m.atDay(20), -(19000 + back * 2700), "WEGMANS 87");
            txn(card, m.atDay(9), -2549, "NETFLIX.COM");
            txn(card, m.atDay(11), -299, "APPLE.COM/BILL");
            txn(card, m.atDay(11), -1099, "APPLE.COM/BILL");
            // A power bill that drops in the fall, and a gym that raised its price.
            txn(checking, m.atDay(15), back == 3 ? -14000 : back == 2 ? -13500 : -9000, "PSE&G CO PAYMENT");
            txn(checking, m.atDay(17), back == 1 ? -2999 : -2499, "PLANET FITNESS CLUB FEES");
            for (int d : new int[] { 3, 10, 17, 24 }) {
                txn(card, m.atDay(d), -(5000 + d * 137 + back * 523), "JOES TAVERN RESTAURANT");
                txn(card, m.atDay(d), -(450 + d * 10), "STARBUCKS 1102");
            }
        }
        // This month's regular charges so far, so none looks stopped late in the month.
        LocalDate today = LocalDate.now();
        String[][] regular = { { "1", "-180000", "AVALON RENT", "checking" }, { "9", "-2549", "NETFLIX.COM", "card" },
                { "11", "-299", "APPLE.COM/BILL", "card" }, { "11", "-1099", "APPLE.COM/BILL", "card" },
                { "15", "-9000", "PSE&G CO PAYMENT", "checking" }, { "17", "-2999", "PLANET FITNESS CLUB FEES", "checking" } };
        for (String[] r : regular) {
            LocalDate day = now.atDay(Integer.parseInt(r[0]));
            if (!day.isAfter(today)) {
                txn("checking".equals(r[3]) ? checking : card, day, Long.parseLong(r[1]), r[2]);
            }
        }
        seeded = true;
    }

    @Test
    void targetsFillThemselvesIn() throws Exception {
        seed();
        JsonNode s = call(get("/api/targets/suggest"), null);
        assertThat(s.get("months").asInt()).isEqualTo(3);
        assertThat(s.get("incomeCents").asLong()).isEqualTo(460000);
        assertThat(s.get("billsCents").asLong()).isEqualTo(180000);
        // A fifth of take-home, rounded down to $50.
        assertThat(s.get("savingsTargetCents").asLong()).isEqualTo(90000);
        List<String> names = new ArrayList<>();
        for (JsonNode t : s.get("targets")) {
            names.add(t.get("name").asText());
            assertThat(t.get("limitCents").asLong() % 2500).isZero();
        }
        assertThat(names).contains("Groceries", "Dining", "Subscriptions").doesNotContain("Housing", "Gas");
        // Everything fits: bills + savings + targets never more than take-home.
        assertThat(s.get("unplannedCents").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(s.get("goal").get("name").asText()).isEqualTo("Emergency fund");
        assertThat(s.get("goal").get("targetCents").asLong()).isPositive();

        JsonNode applied = call(post("/api/targets/suggest"), "{}");
        assertThat(applied.get("targets").asInt()).isEqualTo(names.size());
        assertThat(applied.get("savingsTarget").asBoolean()).isTrue();
        assertThat(applied.get("goal").asBoolean()).isTrue();
        // Doing it again changes nothing you already have.
        JsonNode again = call(post("/api/targets/suggest"), "{}");
        assertThat(again.get("targets").asInt()).isZero();
        assertThat(again.get("savingsTarget").asBoolean()).isFalse();
        assertThat(again.get("goal").asBoolean()).isFalse();
    }

    @Test
    void regularChargesAreFoundAndCoffeeIsNot() throws Exception {
        seed();
        JsonNode o = call(get("/api/recurring"), null);
        List<String> found = new ArrayList<>();
        for (JsonNode i : o.get("items")) {
            found.add(i.get("name").asText() + " " + i.get("amountCents").asLong() + " " + i.get("kind").asText());
        }
        assertThat(found).anyMatch(f -> f.startsWith("Netflix.com 2549 SUBSCRIPTION"));
        assertThat(found).anyMatch(f -> f.contains(" 299 ")).anyMatch(f -> f.contains(" 1099 "));
        assertThat(found).anyMatch(f -> f.contains("180000 BILL"));
        assertThat(found).anyMatch(f -> f.startsWith("Pse&g Co Payment 9000 "));
        assertThat(found).anyMatch(f -> f.startsWith("Planet Fitness Club Fees 2999 "));
        assertThat(found).noneMatch(f -> f.toUpperCase().contains("STARBUCKS") || f.toUpperCase().contains("TAVERN")
                || f.toUpperCase().contains("WEGMANS"));

        // One you add yourself counts too; one you take off doesn't.
        long before = o.get("monthlyCents").asLong();
        call(post("/api/recurring/charges"), "{\"name\":\"Amazon Prime\",\"amountCents\":13900,\"cadence\":\"YEARLY\",\"kind\":\"SUBSCRIPTION\"}");
        long after = call(get("/api/recurring"), null).get("monthlyCents").asLong();
        assertThat(after - before).isEqualTo(Math.round(13900 / 12.0));

        String netflix = null;
        for (JsonNode i : o.get("items")) {
            if (i.get("name").asText().startsWith("Netflix")) {
                netflix = i.get("key").asText();
            }
        }
        call(post("/api/recurring/hide"), "{\"key\":\"" + netflix + "\"}");
        JsonNode hidden = call(get("/api/recurring"), null);
        assertThat(hidden.get("hidden").asInt()).isEqualTo(1);
        for (JsonNode i : hidden.get("items")) {
            assertThat(i.get("name").asText()).doesNotStartWith("Netflix");
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private void txn(long accountId, LocalDate day, long cents, String description) throws Exception {
        call(post("/api/transactions"), String.format(
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
