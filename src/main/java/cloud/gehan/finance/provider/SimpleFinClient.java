package cloud.gehan.finance.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.provider.FetchResult.AccountData;
import cloud.gehan.finance.provider.FetchResult.TxnData;

/**
 * SimpleFIN Bridge (https://www.simplefin.org/protocol.html).
 *
 * <p>You create a Setup Token on the bridge; it is base64 of a one-time claim
 * URL. Claiming it returns an Access URL with credentials embedded
 * (https://user:pass@host/simplefin), which is what gets stored, encrypted, and
 * used for every later pull.
 */
@Component
public class SimpleFinClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json;
    private final ZoneId zone;

    public SimpleFinClient(ObjectMapper json, AppProperties props) {
        this.json = json;
        this.zone = props.zoneId();
    }

    /** Trades a Setup Token for the long-lived Access URL. Each token works once. */
    public String claim(String setupToken) {
        String claimUrl;
        try {
            claimUrl = new String(Base64.getDecoder().decode(setupToken.trim()), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            throw new ProviderException("That doesn't look like a SimpleFIN Setup Token. Copy the whole token from the bridge.", false);
        }
        URI uri = URI.create(claimUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ProviderException("The Setup Token does not point at an https address.", false);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 403) {
            throw new ProviderException("SimpleFIN refused the token. It may already have been used; create a new one on the bridge.", false);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ProviderException("SimpleFIN claim failed with HTTP " + response.statusCode(), false);
        }
        String accessUrl = response.body().trim();
        if (!accessUrl.startsWith("https://")) {
            throw new ProviderException("SimpleFIN returned something other than an access URL.", false);
        }
        return accessUrl;
    }

    public FetchResult fetch(String accessUrl, LocalDate since) {
        URI access = URI.create(accessUrl);
        String userInfo = access.getRawUserInfo();
        if (userInfo == null) {
            throw new ProviderException("Stored SimpleFIN access URL has no credentials; reconnect it.", true);
        }
        String base = access.getScheme() + "://" + access.getHost()
                + (access.getPort() > 0 ? ":" + access.getPort() : "") + access.getRawPath();
        long start = since.atStartOfDay(zone).toEpochSecond();
        URI uri = URI.create(base + "/accounts?pending=1&start-date=" + start);
        String credentials = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ProviderException("SimpleFIN no longer accepts this connection. Create a new Setup Token and reconnect.", true);
        }
        if (response.statusCode() == 402) {
            throw new ProviderException("SimpleFIN says the subscription needs payment.", true);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ProviderException("SimpleFIN returned HTTP " + response.statusCode(), false);
        }
        return parse(response.body());
    }

    FetchResult parse(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new ProviderException("SimpleFIN sent a response that isn't JSON.", e);
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode error : root.path("errors")) {
            warnings.add(error.isTextual() ? error.asText() : error.path("msg").asText(error.toString()));
        }
        for (JsonNode error : root.path("errlist")) {
            warnings.add(error.path("msg").asText(error.toString()));
        }

        List<AccountData> accounts = new ArrayList<>();
        List<TxnData> txns = new ArrayList<>();
        for (JsonNode a : root.path("accounts")) {
            String id = a.path("id").asText();
            String name = a.path("name").asText("Account");
            String institution = text(a.path("org"), "name");
            if (institution == null) {
                institution = text(a.path("org"), "domain");
            }
            long balance = orZero(Money.cents(a.path("balance").asText(null)));
            accounts.add(new AccountData(
                    id, name, institution, lastFour(name),
                    AccountTypeGuesser.guess(name, institution, balance),
                    balance,
                    Money.cents(a.path("available-balance").asText(null)),
                    null,
                    a.path("currency").asText("USD")));

            for (JsonNode t : a.path("transactions")) {
                long posted = t.path("posted").asLong(0);
                long transacted = t.path("transacted_at").asLong(0);
                boolean pending = t.path("pending").asBoolean(false) || posted == 0;
                long when = posted != 0 ? posted : transacted != 0 ? transacted : Instant.now().getEpochSecond();
                String description = firstNonBlank(t.path("description").asText(null), t.path("payee").asText(null),
                        t.path("memo").asText(null), "Transaction");
                txns.add(new TxnData(
                        id,
                        t.path("id").asText(),
                        Instant.ofEpochSecond(when).atZone(zone).toLocalDate(),
                        orZero(Money.cents(t.path("amount").asText(null))),
                        description,
                        text(t, "payee"),
                        pending,
                        null));
            }
        }
        return new FetchResult(accounts, txns, List.of(), List.of(), null, true, warnings);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("Could not reach SimpleFIN: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while talking to SimpleFIN", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static long orZero(Long cents) {
        return cents == null ? 0 : cents;
    }

    /** "Sapphire Preferred (1234)" or "...1234" carries the mask in the name. */
    private static String lastFour(String name) {
        var m = java.util.regex.Pattern.compile("(\\d{4})\\)?\\s*$").matcher(name);
        return m.find() ? m.group(1) : null;
    }
}
