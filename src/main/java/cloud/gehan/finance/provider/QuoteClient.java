package cloud.gehan.finance.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stock and ETF prices from Yahoo Finance's public chart endpoint: free, no key,
 * and close to real time during market hours. It is unofficial, so every caller
 * treats a failure as "keep the last price" rather than an error.
 */
@Component
public class QuoteClient {

    private static final String BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";
    // Yahoo turns away Java's default User-Agent.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final ZoneId MARKET = ZoneId.of("America/New_York");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json;

    public QuoteClient(ObjectMapper json) {
        this.json = json;
    }

    public record Quote(String symbol, String name, double price, Double previousClose, String currency, Instant time) {
    }

    public Quote quote(String symbol) {
        JsonNode result = chart(symbol, "range=5d&interval=1d");
        JsonNode meta = result.path("meta");
        if (!meta.path("regularMarketPrice").isNumber()) {
            throw new ProviderException("No price for " + symbol, false);
        }
        String name = meta.path("longName").isTextual() ? meta.path("longName").asText()
                : meta.path("shortName").asText(symbol);
        Double previous = meta.path("chartPreviousClose").isNumber() ? meta.path("chartPreviousClose").asDouble()
                : meta.path("previousClose").isNumber() ? meta.path("previousClose").asDouble() : null;
        // With a 5-day range chartPreviousClose is the close before that window;
        // the day before today is the second-to-last close in the series.
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        if (closes.isArray() && closes.size() >= 2 && closes.get(closes.size() - 2).isNumber()) {
            previous = closes.get(closes.size() - 2).asDouble();
        }
        return new Quote(symbol.toUpperCase(Locale.ROOT), name, meta.path("regularMarketPrice").asDouble(), previous,
                meta.path("currency").asText("USD"),
                Instant.ofEpochSecond(meta.path("regularMarketTime").asLong(Instant.now().getEpochSecond())));
    }

    /** Daily closes from {@code from} through {@code to}, keyed by trading day. */
    public NavigableMap<LocalDate, Double> history(String symbol, LocalDate from, LocalDate to) {
        long p1 = from.atStartOfDay(MARKET).toEpochSecond();
        long p2 = to.plusDays(1).atStartOfDay(MARKET).toEpochSecond();
        JsonNode result = chart(symbol, "interval=1d&period1=" + p1 + "&period2=" + p2);
        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        JsonNode times = result.path("timestamp");
        JsonNode values = result.path("indicators").path("quote").path(0).path("close");
        for (int i = 0; i < times.size() && i < values.size(); i++) {
            if (values.get(i).isNumber()) {
                closes.put(Instant.ofEpochSecond(times.get(i).asLong()).atZone(MARKET).toLocalDate(), values.get(i).asDouble());
            }
        }
        return closes;
    }


    private JsonNode chart(String symbol, String query) {
        String clean = symbol.trim().toUpperCase(Locale.ROOT);
        if (!clean.matches("[A-Z0-9.\\-^=]{1,15}")) {
            throw new ProviderException("'" + symbol + "' isn't a ticker symbol.", false);
        }
        URI uri = URI.create(BASE + URLEncoder.encode(clean, StandardCharsets.UTF_8) + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("Could not reach the price feed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while fetching prices", e);
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderException("The price feed returned HTTP " + response.statusCode(), e);
        }
        JsonNode error = root.path("chart").path("error");
        if (response.statusCode() == 404 || (error.isObject() && error.size() > 0)) {
            throw new ProviderException("Unknown symbol " + clean, false);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ProviderException("The price feed returned HTTP " + response.statusCode(), false);
        }
        JsonNode result = root.path("chart").path("result").path(0);
        if (result.isMissingNode()) {
            throw new ProviderException("No data for " + clean, false);
        }
        return result;
    }
}
