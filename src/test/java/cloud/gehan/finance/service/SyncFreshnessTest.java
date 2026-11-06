package cloud.gehan.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ConnectionStatus;
import cloud.gehan.finance.model.ProviderType;
import cloud.gehan.finance.provider.FetchResult;
import cloud.gehan.finance.provider.FetchResult.AccountData;
import cloud.gehan.finance.provider.SimpleFinClient;
import cloud.gehan.finance.repo.ConnectionRepository;

/**
 * A SimpleFIN pull can succeed while a bank behind it has stopped updating. The app has
 * to say so, and fetch the missed days once it's fixed.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/test-data/freshness-test.db?busy_timeout=10000")
class SyncFreshnessTest {

    static {
        for (String suffix : List.of("", "-wal", "-shm")) {
            new java.io.File("target/test-data/freshness-test.db" + suffix).delete();
        }
    }

    @Autowired
    SyncService sync;

    @Autowired
    Freshness freshness;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper json;

    @Autowired
    AppProperties props;

    @Test
    void aBankThatStoppedUpdatingIsCalledOutAndItsMissedDaysComeBack() {
        Connection c = connections.save(new Connection(ProviderType.SIMPLEFIN, "SimpleFIN"));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant fiveDaysAgo = now.minus(Duration.ofDays(5));
        Instant thirtyDaysAgo = now.minus(Duration.ofDays(30));

        // First pull: both accounts fresh, nothing wrong.
        tx.executeWithoutResult(s -> sync.apply(c.getId(), result(List.of(
                card("card-1", now), checking("chk-1", now)), List.of())));
        Connection clean = connections.findById(c.getId()).orElseThrow();
        assertThat(clean.getLastCleanSyncAt()).isNotNull();
        assertThat(freshness.problems()).isEmpty();

        // Weeks later the card's bank needs a fresh sign-in: the pull still works, with an error
        // message, and the card's data stops at a date 30 days back.
        tx.executeWithoutResult(s -> {
            Connection conn = connections.findById(c.getId()).orElseThrow();
            conn.setLastCleanSyncAt(fiveDaysAgo);
            connections.save(conn);
        });
        tx.executeWithoutResult(s -> sync.apply(c.getId(), result(List.of(
                card("card-1", thirtyDaysAgo), checking("chk-1", now)), List.of("Chase: sign in again"))));

        Connection warned = connections.findById(c.getId()).orElseThrow();
        assertThat(warned.getStatus()).isEqualTo(ConnectionStatus.OK);
        assertThat(warned.getLastCleanSyncAt()).isEqualTo(fiveDaysAgo);
        // One problem, named by the account that stopped, not "2 bank problems".
        List<Freshness.Problem> problems = freshness.problems();
        assertThat(problems).extracting(Freshness.Problem::status).containsExactly("BEHIND");
        Freshness.Problem behind = problems.get(0);
        assertThat(behind.name()).contains("Card");
        assertThat(behind.line()).contains("nothing new since");
        assertThat(freshness.behind()).hasSize(1);

        // The next pull reaches back to where the card's data stopped, minus the overlap.
        ZoneId zone = props.zoneId();
        LocalDate since = sync.simpleFinSince(warned);
        assertThat(since).isEqualTo(thirtyDaysAgo.atZone(zone).toLocalDate().minusDays(SyncService.SIMPLEFIN_OVERLAP_DAYS));

        // Never further back than SimpleFIN's 90 days (midnight 89 days back, so today's hours fit).
        tx.executeWithoutResult(s -> sync.apply(c.getId(), result(List.of(
                card("card-1", now.minus(Duration.ofDays(200))), checking("chk-1", now)), List.of("Chase: sign in again"))));
        assertThat(sync.simpleFinSince(connections.findById(c.getId()).orElseThrow()))
                .isEqualTo(LocalDate.now(zone).minusDays(SyncService.SIMPLEFIN_FIRST_DAYS - 1));

        // Removed on the bridge: no longer listed, so no longer "behind", and it stops pulling syncs back.
        tx.executeWithoutResult(s -> sync.apply(c.getId(), result(List.of(checking("chk-1", now)), List.of())));
        assertThat(freshness.behind()).isEmpty();
        assertThat(freshness.problems()).isEmpty();
        assertThat(sync.simpleFinSince(connections.findById(c.getId()).orElseThrow()))
                .isEqualTo(LocalDate.now(zone).minusDays(SyncService.SIMPLEFIN_OVERLAP_DAYS));
    }

    @Test
    void theBridgesOwnNoticesArentABanksProblem() throws Exception {
        SimpleFinClient client = new SimpleFinClient(json, props);
        var parse = SimpleFinClient.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        FetchResult r = (FetchResult) parse.invoke(client, "{\"errors\":[\"Requested date range exceeds recommended range "
                + "of 45 days.\",\"Chase needs attention\"],\"errlist\":["
                + "{\"code\":\"gen.api\",\"msg\":\"Requested date range exceeds recommended range of 45 days.\"},"
                + "{\"code\":\"con.auth\",\"msg\":\"Chase needs attention\"}],\"accounts\":[]}");
        assertThat(r.warnings()).containsExactly("Chase needs attention");
        // An older bridge without errlist: plain strings, minus its notes about the request.
        FetchResult old = (FetchResult) parse.invoke(client, "{\"errors\":[\"Requested date range exceeds limit of 90 days "
                + "and was capped.\",\"Connection to Chase may need attention\"],\"accounts\":[]}");
        assertThat(old.warnings()).containsExactly("Connection to Chase may need attention");
    }

    @Test
    void simpleFinsBalanceDateIsRead() throws Exception {
        SimpleFinClient client = new SimpleFinClient(json, props);
        var parse = SimpleFinClient.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        FetchResult r = (FetchResult) parse.invoke(client, "{\"errors\":[\"Connection to Chase may need attention\"],"
                + "\"accounts\":[{\"id\":\"a1\",\"name\":\"Freedom Unlimited\",\"currency\":\"USD\",\"balance\":\"-120.50\","
                + "\"balance-date\":1793000000,\"org\":{\"name\":\"Chase\"},\"transactions\":[]}]}");
        assertThat(r.accounts().get(0).balanceDate()).isEqualTo(Instant.ofEpochSecond(1793000000L));
        assertThat(r.warnings()).containsExactly("Connection to Chase may need attention");
    }

    private static FetchResult result(List<AccountData> accounts, List<String> warnings) {
        return new FetchResult(accounts, List.of(), List.of(), List.of(), null, true, warnings);
    }

    private static AccountData card(String id, Instant asOf) {
        return new AccountData(id, "Card", "Chase", "1234", AccountType.CREDIT_CARD, -12000, null, null, "USD", asOf);
    }

    private static AccountData checking(String id, Instant asOf) {
        return new AccountData(id, "Checking", "SoFi", "5678", AccountType.CHECKING, 250000, null, null, "USD", asOf);
    }
}
