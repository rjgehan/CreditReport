package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Twice-a-month pay whose description carries punctuation and a date, the way
 * banks send it: "ACME, INC. 0915 EDI PYMNTS". "This is my pay" has to file every
 * one of them, and the month's income has to show it, without catching refunds or
 * undoing choices made elsewhere.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/paycheck-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class PaycheckTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void thisIsMyPayFilesEveryDeposit() throws Exception {
        long checking = account("Checking", "CHECKING");
        long card = account("Card", "CREDIT_CARD");
        int deposits = payHistory(checking, "ACME, INC. %s EDI PYMNTS");
        // An expense reimbursement from the same payer, filed by hand.
        JsonNode reimbursement = txn(checking, LocalDate.now().minusDays(20), 8640, "ACME, INC. 0924 EDI PYMNTS");
        patchCategory(reimbursement.get("id").asLong(), "Refunds & Credits");

        JsonNode detected = detected("ACME INC EDI");
        assertThat(detected.get("frequency").asText()).isEqualTo("SEMIMONTHLY");
        assertThat(detected.get("tracked").asBoolean()).isFalse();

        String body = "{\"pattern\":\"ACME INC EDI\"}";
        JsonNode filed = call(post("/api/paychecks/track"), body).get("filed");
        assertThat(filed.get("matched").asInt()).isEqualTo(deposits);
        assertThat(filed.get("refiled").asInt()).isEqualTo(deposits);
        assertThat(detected("ACME INC EDI").get("tracked").asBoolean()).isTrue();
        assertThat(category(reimbursement.get("id").asLong())).isEqualTo("Refunds & Credits");

        // A second tap changes nothing and adds no second rule.
        filed = call(post("/api/paychecks/track"), body).get("filed");
        assertThat(filed.get("matched").asInt()).isEqualTo(deposits);
        assertThat(filed.get("refiled").asInt()).isZero();
        assertThat(rulesWithPattern("ACME INC EDI")).isEqualTo(1);

        // Last month's pay is income; this month's is either here or expected.
        assertThat(call(get("/api/summary"), null).get("plan").get("incomeCents").asLong()).isPositive();

        // Next payday is filed by the rule; the same words on a card are a refund, and a
        // small reimbursement isn't pay either.
        assertThat(txn(checking, LocalDate.now(), 215000, "ACME, INC. 9999 EDI PYMNTS").get("categoryName").asText())
                .isEqualTo("Paycheck");
        assertThat(txn(card, LocalDate.now(), 250000, "ACME, INC. 9999 EDI PYMNTS").get("categoryName").asText())
                .isNotEqualTo("Paycheck");
        assertThat(txn(checking, LocalDate.now(), 4210, "ACME, INC. 9998 EDI PYMNTS").get("categoryName").asText())
                .isNotEqualTo("Paycheck");
    }

    @Test
    void aPaystubFilesTheDepositsItsWordsFind() throws Exception {
        long checking = account("Globex checking", "CHECKING");
        long card = account("Globex card", "CREDIT_CARD");
        int deposits = payHistory(checking, "GLOBEX CORPORATION DEP %s");
        JsonNode purchase = txn(card, LocalDate.now().minusDays(3), -4000, "GLOBEX STORE 114");
        JsonNode refund = txn(card, LocalDate.now().minusDays(2), 4000, "GLOBEX STORE 114 RETURN");

        // A rule of yours with the same words stays as you made it.
        call(post("/api/rules"), String.format("{\"pattern\":\"GLOBEX\",\"categoryId\":%d,\"sign\":\"OUT\"}",
                categoryId("Shopping")));

        // Words that find nothing say so instead of pretending.
        JsonNode miss = call(post("/api/paystubs"), paystub("INITECH")).get("filed");
        assertThat(miss.get("matched").asInt()).isZero();

        JsonNode filed = call(post("/api/paystubs"), paystub("Globex")).get("filed");
        assertThat(filed.get("matched").asInt()).isEqualTo(deposits);
        assertThat(filed.get("refiled").asInt()).isEqualTo(deposits);
        assertThat(detected("GLOBEX CORPORATION DEP").get("tracked").asBoolean()).isTrue();

        // The paystub's one word never became a Paycheck rule, so store refunds stay refunds.
        assertThat(rulesWithPattern("GLOBEX")).isEqualTo(1);
        assertThat(category(purchase.get("id").asLong())).isEqualTo("Shopping");
        assertThat(category(refund.get("id").asLong())).isNotEqualTo("Paycheck");
    }

    // --- helpers ---------------------------------------------------------------------

    /** Pay on the 15th and the last day of each of the last few months, up to today. */
    private int payHistory(long accountId, String description) throws Exception {
        LocalDate today = LocalDate.now();
        int deposits = 0;
        for (int back = 4; back >= 0; back--) {
            YearMonth m = YearMonth.from(today).minusMonths(back);
            for (LocalDate day : new LocalDate[] { m.atDay(15), m.atEndOfMonth() }) {
                if (!day.isAfter(today)) {
                    txn(accountId, day, 215000, String.format(description,
                            String.format("%02d%02d", day.getMonthValue(), day.getDayOfMonth())));
                    deposits++;
                }
            }
        }
        return deposits;
    }

    private long account(String name, String type) throws Exception {
        return call(post("/api/accounts"), String.format("{\"name\":\"%s\",\"type\":\"%s\",\"balanceCents\":0}", name, type))
                .get("id").asLong();
    }

    private JsonNode txn(long accountId, LocalDate day, long cents, String description) throws Exception {
        return call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":%d,\"description\":\"%s\"}", accountId, day, cents, description));
    }

    private void patchCategory(long txnId, String category) throws Exception {
        call(patch("/api/transactions/" + txnId), String.format("{\"categoryId\":%d}", categoryId(category)));
    }

    private String category(long txnId) throws Exception {
        for (JsonNode t : call(get("/api/transactions?size=200"), null).get("items")) {
            if (t.get("id").asLong() == txnId) {
                return t.get("categoryName").asText();
            }
        }
        throw new AssertionError("no transaction " + txnId);
    }

    private long categoryId(String name) throws Exception {
        for (JsonNode c : call(get("/api/categories"), null)) {
            if (name.equals(c.get("name").asText())) {
                return c.get("id").asLong();
            }
        }
        throw new AssertionError("no category " + name);
    }

    private JsonNode detected(String pattern) throws Exception {
        for (JsonNode d : call(get("/api/paychecks"), null).get("overview").get("detected")) {
            if (pattern.equals(d.get("pattern").asText())) {
                return d;
            }
        }
        throw new AssertionError("not detected: " + pattern);
    }

    private long rulesWithPattern(String pattern) throws Exception {
        long n = 0;
        for (JsonNode r : call(get("/api/rules"), null)) {
            if (pattern.equals(r.get("pattern").asText())) {
                n++;
            }
        }
        return n;
    }

    private static String paystub(String pattern) {
        return String.format("{\"employer\":\"%s\",\"matchPattern\":\"%s\",\"frequency\":\"SEMIMONTHLY\","
                + "\"grossCents\":300000,\"federalTaxCents\":40000,\"stateTaxCents\":10000,\"ficaCents\":22950,"
                + "\"retirementCents\":12050,\"benefitsCents\":0,\"otherDeductionsCents\":0,\"employerMatchCents\":6000}",
                pattern, pattern);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(user("me")).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String out = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }
}
