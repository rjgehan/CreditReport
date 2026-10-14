package cloud.gehan.finance.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cloud.gehan.finance.config.AppProperties;

/**
 * Logos for well-known merchants and banks: Netflix's N next to a Netflix
 * charge. A transaction's description is matched against known names to find a
 * website, and the site's icon is fetched once by the server and cached on disk.
 * The phone only ever asks this server, so nothing outside learns where you shop.
 */
@Service
public class MerchantLogos {

    private static final Logger log = LoggerFactory.getLogger(MerchantLogos.class);

    /** Words in a description (as rules match them) to the merchant's website. */
    static final Map<String, String> DOMAINS = new LinkedHashMap<>();

    static {
        add("netflix.com", "NETFLIX");
        add("spotify.com", "SPOTIFY");
        add("hulu.com", "HULU");
        add("disneyplus.com", "DISNEY PLUS", "DISNEYPLUS");
        add("max.com", "HBO MAX", "MAX.COM");
        add("apple.com", "APPLE.COM/BILL", "ICLOUD", "APPLE STORE", "APPLECARD", "APPLE CARD");
        add("youtube.com", "YOUTUBE");
        add("paramountplus.com", "PARAMOUNT");
        add("peacocktv.com", "PEACOCK");
        add("audible.com", "AUDIBLE");
        add("patreon.com", "PATREON");
        add("openai.com", "OPENAI", "CHATGPT");
        add("anthropic.com", "ANTHROPIC", "CLAUDE.AI");
        add("amazon.com", "AMAZON", "AMZN");
        add("wholefoodsmarket.com", "WHOLE FOODS", "WHOLEFDS");
        add("traderjoes.com", "TRADER JOE");
        add("kroger.com", "KROGER");
        add("safeway.com", "SAFEWAY");
        add("wegmans.com", "WEGMANS");
        add("publix.com", "PUBLIX");
        add("aldi.us", "ALDI");
        add("shoprite.com", "SHOPRITE");
        add("stopandshop.com", "STOP & SHOP");
        add("costco.com", "COSTCO");
        add("instacart.com", "INSTACART");
        add("starbucks.com", "STARBUCKS");
        add("mcdonalds.com", "MCDONALD");
        add("chipotle.com", "CHIPOTLE");
        add("doordash.com", "DOORDASH");
        add("ubereats.com", "UBER EATS", "UBEREATS");
        add("grubhub.com", "GRUBHUB");
        add("dunkindonuts.com", "DUNKIN");
        add("sweetgreen.com", "SWEETGREEN");
        add("chick-fil-a.com", "CHICK-FIL-A");
        add("panerabread.com", "PANERA");
        add("shakeshack.com", "SHAKE SHACK");
        add("tacobell.com", "TACO BELL");
        add("uber.com", "UBER");
        add("lyft.com", "LYFT");
        add("amtrak.com", "AMTRAK");
        add("njtransit.com", "NJ TRANSIT");
        add("target.com", "TARGET");
        add("walmart.com", "WALMART");
        add("bestbuy.com", "BEST BUY");
        add("etsy.com", "ETSY");
        add("ebay.com", "EBAY");
        add("ikea.com", "IKEA");
        add("homedepot.com", "HOME DEPOT");
        add("lowes.com", "LOWE");
        add("tjmaxx.tjx.com", "TJ MAXX", "TJMAXX");
        add("verizon.com", "VERIZON");
        add("att.com", "AT&T");
        add("xfinity.com", "COMCAST", "XFINITY");
        add("t-mobile.com", "T-MOBILE");
        add("spectrum.com", "SPECTRUM");
        add("pseg.com", "PSE&G");
        add("coned.com", "CON ED", "CONEDISON");
        add("cvs.com", "CVS");
        add("walgreens.com", "WALGREENS");
        add("geico.com", "GEICO");
        add("statefarm.com", "STATE FARM");
        add("progressive.com", "PROGRESSIVE");
        add("allstate.com", "ALLSTATE");
        add("lemonade.com", "LEMONADE");
        add("steampowered.com", "STEAM");
        add("ticketmaster.com", "TICKETMASTER");
        add("stubhub.com", "STUBHUB");
        add("amctheatres.com", "AMC");
        add("playstation.com", "PLAYSTATION");
        add("nintendo.com", "NINTENDO");
        add("delta.com", "DELTA AIR");
        add("united.com", "UNITED AIRLINES");
        add("jetblue.com", "JETBLUE");
        add("southwest.com", "SOUTHWEST");
        add("marriott.com", "MARRIOTT");
        add("hilton.com", "HILTON");
        add("hyatt.com", "HYATT");
        add("airbnb.com", "AIRBNB");
        add("expedia.com", "EXPEDIA");
        add("venmo.com", "VENMO");
        add("zellepay.com", "ZELLE");
        add("paypal.com", "PAYPAL");
        add("planetfitness.com", "PLANET FITNESS");
        add("sephora.com", "SEPHORA");
        add("ulta.com", "ULTA");
        add("webull.com", "WEBULL");
        add("robinhood.com", "ROBINHOOD");
        add("fidelity.com", "FIDELITY");
        add("vanguard.com", "VANGUARD");
        add("schwab.com", "SCHWAB");
        add("chase.com", "CHASE");
        add("sofi.com", "SOFI");
        add("capitalone.com", "CAPITAL ONE");
        add("discover.com", "DISCOVER");
        add("americanexpress.com", "AMEX", "AMERICAN EXPRESS");
        add("usaa.com", "USAA");
        add("ally.com", "ALLY");
        add("pcsretirement.com", "PCS RETIREMENT");
        add("nelnet.com", "NELNET");
        add("navient.com", "NAVIENT");
        add("mohela.com", "MOHELA");
        add("salliemae.com", "SALLIE MAE");
        add("irs.gov", "IRS");
        add("adp.com", "ADP");
        add("gusto.com", "GUSTO");
    }

    private static void add(String domain, String... names) {
        for (String name : names) {
            DOMAINS.put(name, domain);
        }
    }

    record Known(Pattern pattern, String domain) {
    }

    /** Longest names first, so UBER EATS wins over UBER. */
    private static final List<Known> MATCHERS = new ArrayList<>();
    private static final Set<String> ALLOWED = Set.copyOf(DOMAINS.values());

    static {
        DOMAINS.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .forEach(e -> MATCHERS.add(new Known(Categorizer.wordPattern(e.getKey()), e.getValue())));
    }

    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MerchantLogos(AppProperties props) {
        this.cache = Path.of(props.dataDir(), "logos");
    }

    /** The website for whatever this text names, if it's a merchant or bank this app knows. */
    public static String domainFor(String... texts) {
        StringBuilder all = new StringBuilder();
        for (String t : texts) {
            if (t != null) {
                all.append(' ').append(t);
            }
        }
        String text = all.toString().toUpperCase(Locale.ROOT);
        for (Known k : MATCHERS) {
            if (k.pattern().matcher(text).find()) {
                return k.domain();
            }
        }
        return null;
    }

    /**
     * The cached icon for a known domain, fetching it the first time. Empty for
     * a domain this app doesn't know (so this can't be used as an open proxy) or
     * one with no icon.
     */
    public Optional<byte[]> icon(String domain) {
        if (domain == null || !ALLOWED.contains(domain)) {
            return Optional.empty();
        }
        Path file = cache.resolve(domain + ".png");
        Path missing = cache.resolve(domain + ".none");
        try {
            if (Files.exists(file)) {
                return Optional.of(Files.readAllBytes(file));
            }
            // A known miss is remembered for a week, then tried again.
            if (Files.exists(missing) && Files.getLastModifiedTime(missing).toInstant()
                    .isAfter(Instant.now().minus(Duration.ofDays(7)))) {
                return Optional.empty();
            }
            Files.createDirectories(cache);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://www.google.com/s2/favicons?sz=64&domain=" + domain))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 200 && type.startsWith("image/") && response.body().length > 0) {
                Files.write(file, response.body());
                return Optional.of(response.body());
            }
            // Only a definite "no icon" is remembered; a hiccup (429, 5xx) just tries again next time.
            if (response.statusCode() == 404 || response.statusCode() == 200) {
                Files.writeString(missing, String.valueOf(response.statusCode()));
            }
            return Optional.empty();
        } catch (IOException e) {
            log.debug("No logo for {}: {}", domain, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
