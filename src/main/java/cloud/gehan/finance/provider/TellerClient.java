package cloud.gehan.finance.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.TellerProperties;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.provider.FetchResult.AccountData;
import cloud.gehan.finance.provider.FetchResult.TxnData;

/**
 * Teller's REST API (https://teller.io/docs/api). Teller Connect runs in the
 * browser and hands back an access token for one enrollment (one bank login);
 * every call after that is HTTP Basic with the token as the username, over a
 * TLS connection that presents your Teller client certificate.
 */
@Component
public class TellerClient {

    private static final String BASE = "https://api.teller.io";
    private static final Pattern PAYMENT = Pattern.compile("\\b(PAYMENT|THANK YOU|AUTOPAY|AUTOMATIC PAY)\\b");

    private final ObjectMapper json;
    private final TellerProperties props;
    private volatile HttpClient http;

    public TellerClient(ObjectMapper json, TellerProperties props) {
        this.json = json;
        this.props = props;
    }

    public boolean configured() {
        return props.configured();
    }

    public FetchResult fetch(String accessToken, String institutionFallback) {
        List<AccountData> accounts = new ArrayList<>();
        List<TxnData> txns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (JsonNode a : get(accessToken, "/accounts")) {
            if ("closed".equals(a.path("status").asText())) {
                continue;
            }
            String id = a.path("id").asText();
            AccountType type = mapType(a.path("type").asText(), a.path("subtype").asText(""));
            String institution = a.path("institution").path("name").asText(institutionFallback);

            JsonNode balances = get(accessToken, "/accounts/" + id + "/balances");
            Long ledger = Money.cents(balances.path("ledger").asText(null));
            Long available = Money.cents(balances.path("available").asText(null));
            long balance = ledger == null ? (available == null ? 0 : available) : ledger;
            Long limit = null;
            if (type.liability()) {
                // Teller reports what you owe on a card as a positive ledger balance.
                if (ledger != null && available != null) {
                    limit = ledger + available;
                }
                balance = -Math.abs(balance);
            }
            accounts.add(new AccountData(id, a.path("name").asText("Account"), institution,
                    a.path("last_four").isTextual() ? a.path("last_four").asText() : null,
                    type, balance, available, limit, a.path("currency").asText("USD")));

            List<TxnData> accountTxns = new ArrayList<>();
            for (JsonNode t : get(accessToken, "/accounts/" + id + "/transactions")) {
                Long amount = Money.cents(t.path("amount").asText(null));
                if (amount == null) {
                    continue;
                }
                JsonNode details = t.path("details");
                String counterparty = details.path("counterparty").path("name").isTextual()
                        ? details.path("counterparty").path("name").asText() : null;
                String category = details.path("category").isTextual() ? "teller:" + details.path("category").asText() : null;
                accountTxns.add(new TxnData(id, t.path("id").asText(), LocalDate.parse(t.path("date").asText()),
                        amount, t.path("description").asText("Transaction"), counterparty,
                        "pending".equals(t.path("status").asText()), category));
            }
            if (type == AccountType.CREDIT_CARD && purchasesArePositive(accountTxns)) {
                accountTxns.replaceAll(t -> new TxnData(t.accountExternalId(), t.externalId(), t.day(),
                        -t.amountCents(), t.description(), t.merchant(), t.pending(), t.providerCategory()));
            }
            txns.addAll(accountTxns);
        }
        // Teller lists everything it knows each time, pending included.
        return new FetchResult(accounts, txns, List.of(), List.of(), null, true, warnings);
    }

    /**
     * Teller's sign convention on card accounts has varied by institution. Here a
     * purchase must be negative, so if the payments are the negative ones (or,
     * with no payments in view, most rows are positive) the account is flipped.
     */
    static boolean purchasesArePositive(List<TxnData> txns) {
        long paymentsNegative = 0;
        long paymentsPositive = 0;
        long positive = 0;
        for (TxnData t : txns) {
            if (PAYMENT.matcher(t.description().toUpperCase(Locale.ROOT)).find()) {
                if (t.amountCents() < 0) {
                    paymentsNegative++;
                } else {
                    paymentsPositive++;
                }
            }
            if (t.amountCents() > 0) {
                positive++;
            }
        }
        if (paymentsNegative + paymentsPositive > 0) {
            return paymentsNegative > paymentsPositive;
        }
        return !txns.isEmpty() && positive * 10 > txns.size() * 6;
    }

    static AccountType mapType(String type, String subtype) {
        if ("credit".equals(type)) {
            return AccountType.CREDIT_CARD;
        }
        return switch (subtype) {
            case "savings", "money_market", "certificate_of_deposit", "treasury" -> AccountType.SAVINGS;
            default -> AccountType.CHECKING;
        };
    }

    private JsonNode get(String accessToken, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((accessToken + ":").getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("Could not reach Teller: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while talking to Teller", e);
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderException("Teller returned HTTP " + response.statusCode() + " without JSON", e);
        }
        if (response.statusCode() / 100 != 2) {
            String code = body.path("error").path("code").asText("http." + response.statusCode());
            String message = body.path("error").path("message").asText("Teller error");
            boolean reauth = code.startsWith("enrollment.") || response.statusCode() == 401;
            throw new ProviderException(message + " (" + code + ")", reauth);
        }
        return body;
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = build();
                }
                c = http;
            }
        }
        return c;
    }

    private HttpClient build() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        if (props.hasCertificate()) {
            try {
                builder.sslContext(Pem.clientContext(Path.of(props.certificate()), Path.of(props.privateKey())));
            } catch (IOException | GeneralSecurityException e) {
                throw new ProviderException("Could not load the Teller certificate (" + e.getMessage()
                        + "). Check TELLER_CERTIFICATE and TELLER_PRIVATE_KEY.", e);
            }
        } else if (!props.sandbox()) {
            throw new ProviderException("Teller's " + props.env() + " environment needs your client certificate: "
                    + "set TELLER_CERTIFICATE and TELLER_PRIVATE_KEY.", false);
        }
        return builder.build();
    }
}
