package cloud.gehan.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Payments to people wait for you, checks, your own categories, and bills with their old payments. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/sorting-test.db?busy_timeout=10000")
@AutoConfigureMockMvc
class SortingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void paymentsToPeopleAreNeverGuessed() throws Exception {
        long checking = account("People checking");
        JsonNode venmo = txn(checking, LocalDate.now(), -2500, "VENMO PAYMENT 1034567 WEB ID: 3264681992");
        assertThat(venmo.get("categoryName").asText()).isEqualTo("Uncategorized");
        assertThat(venmo.get("categorySource").asText()).isEqualTo("RULE");
        assertThat(call(get("/api/transactions/" + venmo.get("id").asLong() + "/reason"), null).get("text").asText())
                .isEqualTo("Built-in rule: VENMO");
        // A Zelle to a pizza place is still a payment to someone.
        assertThat(txn(checking, LocalDate.now(), -3000, "ZELLE TO JOES PIZZA").get("categoryName").asText())
                .isEqualTo("Uncategorized");
        // Square takes payments for barbers too.
        assertThat(txn(checking, LocalDate.now(), -4000, "SQ *CORNER BARBER").get("categoryName").asText())
                .isNotEqualTo("Dining");
        // And "No category" finds them.
        long none = call(get("/api/transactions?category=none&account=" + checking), null).get("total").asLong();
        assertThat(none).isGreaterThanOrEqualTo(2);
    }

    @Test
    void checksTrackWhatYouHaveLookedAt() throws Exception {
        long checking = account("Review checking");
        long a = txn(checking, LocalDate.now(), -1200, "CORNER STORE 1").get("id").asLong();
        txn(checking, LocalDate.now(), -1300, "CORNER STORE 2");
        txn(checking, LocalDate.now(), -1400, "CORNER STORE 3");

        // Typed in by hand, so already right; un-check them to start from three to review.
        call(post("/api/transactions/review?account=" + checking), "{\"reviewed\":false}");
        assertThat(toReview(checking)).isEqualTo(3);

        call(patch("/api/transactions/" + a), "{\"reviewed\":true}");
        assertThat(toReview(checking)).isEqualTo(2);
        JsonNode todo = call(get("/api/transactions?review=todo&account=" + checking), null);
        assertThat(todo.get("total").asLong()).isEqualTo(2);

        // Picking a category checks it off; handing it back to the rules un-checks it.
        long groceries = categoryId("Groceries");
        long b = todo.get("items").get(0).get("id").asLong();
        assertThat(call(patch("/api/transactions/" + b), "{\"categoryId\":" + groceries + "}")
                .get("transaction").get("reviewed").asBoolean()).isTrue();
        assertThat(toReview(checking)).isEqualTo(1);
        call(patch("/api/transactions/" + b), "{\"clearCategory\":true}");
        assertThat(toReview(checking)).isEqualTo(2);

        assertThat(call(post("/api/transactions/review?account=" + checking), "{\"reviewed\":true}").get("changed").asInt())
                .isEqualTo(2);
        assertThat(toReview(checking)).isZero();
    }

    @Test
    void yourOwnCategoriesComeAndGo() throws Exception {
        long checking = account("Category checking");
        JsonNode made = call(post("/api/categories"), "{\"name\":\"Climbing\",\"kind\":\"EXPENSE\"}");
        long climbing = made.get("id").asLong();
        assertThat(made.get("roles").size()).isZero();

        long t = txn(checking, LocalDate.now(), -9000, "BOULDER GYM 0042").get("id").asLong();
        call(patch("/api/transactions/" + t), "{\"categoryId\":" + climbing + "}");
        call(patch("/api/categories/" + climbing), "{\"name\":\"Bouldering\"}");

        mvc.perform(with(delete("/api/categories/" + climbing))).andExpect(status().isNoContent());
        JsonNode after = find(checking, t);
        assertThat(after.get("categoryName").asText()).isNotEqualTo("Bouldering");
        assertThat(after.get("reviewed").asBoolean()).isFalse();

        // Any category can be renamed, even one the app files things into.
        long transfer = categoryId("Transfer");
        call(patch("/api/categories/" + transfer), "{\"name\":\"Moves\"}");
        // Deleting it needs somewhere for transfers to go, of the same kind...
        mvc.perform(with(delete("/api/categories/" + transfer))).andExpect(status().isBadRequest());
        mvc.perform(with(delete("/api/categories/" + transfer + "?moveTo=" + categoryId("Groceries"))))
                .andExpect(status().isBadRequest());
        // ...and then the job moves with them.
        long cardPayment = categoryId("Credit Card Payment");
        mvc.perform(with(delete("/api/categories/" + transfer + "?moveTo=" + cardPayment))).andExpect(status().isNoContent());
        JsonNode merged = null;
        for (JsonNode c : call(get("/api/categories"), null)) {
            if (c.get("id").asLong() == cardPayment) {
                merged = c;
            }
        }
        assertThat(merged.get("roles").toString()).contains("TRANSFER").contains("CARD_PAYMENT");
        // Money moving inside a savings account now lands there.
        long savings = call(post("/api/accounts"), "{\"name\":\"Category savings\",\"type\":\"SAVINGS\",\"balanceCents\":0}")
                .get("id").asLong();
        assertThat(txn(savings, LocalDate.now(), -5000, "INTERNAL MOVE 1").get("categoryId").asLong()).isEqualTo(cardPayment);

        // An ordinary category moves into another one, transactions and all.
        long coffee = call(post("/api/categories"), "{\"name\":\"Coffee\",\"kind\":\"EXPENSE\"}").get("id").asLong();
        long cup = txn(checking, LocalDate.now(), -550, "BLUE BOTTLE 22").get("id").asLong();
        call(patch("/api/transactions/" + cup), "{\"categoryId\":" + coffee + "}");
        long dining = categoryId("Dining");
        mvc.perform(with(delete("/api/categories/" + coffee + "?moveTo=" + dining))).andExpect(status().isNoContent());
        assertThat(find(checking, cup).get("categoryId").asLong()).isEqualTo(dining);
    }

    @Test
    void billsCountOldPaymentsYouAddByHand() throws Exception {
        long checking = account("Bills checking");
        long loans = categoryId("Loans");
        txn(checking, LocalDate.now().withDayOfMonth(1), -31000, "MOHELA STUDENT LN PMT");

        YearMonth now = YearMonth.now();
        String days = String.format("[\"%s\",\"%s\",\"%s\"]", now.minusMonths(1).atDay(3), now.minusMonths(2).atDay(3),
                now.minusMonths(3).atDay(3));
        JsonNode added = call(post("/api/bills/" + loans + "/payments"),
                String.format("{\"accountId\":%d,\"amountCents\":31000,\"days\":%s}", checking, days));
        assertThat(added.get("added").asInt()).isEqualTo(3);

        JsonNode bill = null;
        for (JsonNode b : call(get("/api/bills"), null).get("bills")) {
            if (b.get("categoryId").asLong() == loans) {
                bill = b;
            }
        }
        assertThat(bill).isNotNull();
        assertThat(bill.get("allTimeCents").asLong()).isEqualTo(4 * 31000);
        assertThat(bill.get("months").get(0).get("paidCents").asLong()).isEqualTo(31000);
        assertThat(bill.get("months").get(3).get("paidCents").asLong()).isEqualTo(31000);
        assertThat(bill.get("usualAccountId").asLong()).isEqualTo(checking);

        // An old payment typed in by hand can be corrected.
        JsonNode old = call(get("/api/transactions?category=" + loans + "&account=" + checking + "&from="
                + now.minusMonths(3).atDay(1) + "&to=" + now.minusMonths(3).atEndOfMonth()), null).get("items").get(0);
        assertThat(old.get("manual").asBoolean()).isTrue();
        call(patch("/api/transactions/" + old.get("id").asLong()), "{\"amountCents\":-30000}");
        assertThat(find(checking, old.get("id").asLong()).get("amountCents").asLong()).isEqualTo(-30000);
    }

    // --- helpers ---------------------------------------------------------------------

    private long account(String name) throws Exception {
        return call(post("/api/accounts"), String.format("{\"name\":\"%s\",\"type\":\"CHECKING\",\"balanceCents\":0}", name))
                .get("id").asLong();
    }

    private JsonNode txn(long accountId, LocalDate day, long cents, String description) throws Exception {
        return call(post("/api/transactions"), String.format(
                "{\"accountId\":%d,\"day\":\"%s\",\"amountCents\":%d,\"description\":\"%s\"}", accountId, day, cents, description));
    }

    private long toReview(long accountId) throws Exception {
        return call(get("/api/transactions?account=" + accountId), null).get("toReview").asLong();
    }

    private JsonNode find(long accountId, long txnId) throws Exception {
        for (JsonNode t : call(get("/api/transactions?size=200&account=" + accountId), null).get("items")) {
            if (t.get("id").asLong() == txnId) {
                return t;
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

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder request) {
        return request.with(user("me")).with(csrf());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, body, status().is2xxSuccessful());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body, ResultMatcher expect) throws Exception {
        with(request);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String out = mvc.perform(request).andExpect(expect).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }
}
