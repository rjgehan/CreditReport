package cloud.gehan.finance;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiRequiresSignIn() throws Exception {
        mvc.perform(get("/api/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIsRefused() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "me").param("password", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rightPasswordSignsIn() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "me").param("password", "test-password"))
                .andExpect(status().isNoContent());
    }

    @Test
    void summaryLoads() throws Exception {
        mvc.perform(get("/api/summary").with(user("me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netWorthCents").exists());
    }

    @Test
    void writesNeedCsrf() throws Exception {
        mvc.perform(post("/api/accounts").with(user("me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cash\",\"type\":\"CHECKING\",\"balanceCents\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shortcutNeedsItsToken() throws Exception {
        mvc.perform(get("/api/shortcut/prompt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shortcut/prompt").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/shortcut/answer").header("Authorization", "Bearer wrong").content("1 | Dining"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shortcutWorksWithAFreshToken() throws Exception {
        String body = mvc.perform(post("/api/shortcut/token").with(user("me")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/shortcut/prompt").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/shortcut/answer").header("Authorization", "Bearer " + token).content("none"))
                .andExpect(status().isOk());
    }

    @Test
    void manualAccountRoundTrip() throws Exception {
        mvc.perform(post("/api/accounts").with(user("me")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Wallet\",\"type\":\"CHECKING\",\"balanceCents\":12345}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceCents").value(12345));
        mvc.perform(get("/api/summary").with(user("me")))
                .andExpect(jsonPath("$.netWorthCents").value(12345));
    }
}
