package cloud.gehan.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ConnectionStatus;
import cloud.gehan.finance.model.ProviderType;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.ConnectionRepository;

/** Everything the Home screen shows, in one request; Accounts reads the net worth history from it too. */
@Service
@Transactional(readOnly = true)
public class SummaryService {

    static final int HISTORY_MONTHS = 6;
    /** The Home sparkline: long enough to see a trend, short enough to stay "lately". */
    static final int SPARK_DAYS = 30;

    private final AccountRepository accounts;
    private final ConnectionRepository connections;
    private final FlowService flow;
    private final TargetService targets;
    private final NetWorthService netWorth;
    private final Budget budget;
    private final ZoneId zone;

    public SummaryService(AccountRepository accounts, ConnectionRepository connections, FlowService flow,
            TargetService targets, NetWorthService netWorth, Budget budget, AppProperties props) {
        this.accounts = accounts;
        this.connections = connections;
        this.flow = flow;
        this.targets = targets;
        this.netWorth = netWorth;
        this.budget = budget;
        this.zone = props.zoneId();
    }

    public record Point(LocalDate day, long cents) {
    }

    public record Group(String name, long cents, int count) {
    }

    public record Card(Long id, String name, String institution, String mask, long balanceCents, Long limitCents,
            Double utilization, LocalDate dueDate, Long minimumPaymentCents, Long statementBalanceCents) {
    }

    public record Problem(Long connectionId, String name, String status, String error) {
    }

    /**
     * @param netWorthChangeCents change over the sparkline's window; null without enough history
     * @param netWorthChangePercent the same as a percentage of where it started
     * @param netWorthChangeDays how many days that change covers (30 once there is that much history)
     */
    public record Home(String month, long netWorthCents, long assetsCents, long liabilitiesCents, List<Group> groups,
            List<Point> netWorthHistory, List<Point> netWorthSpark, Long netWorthChangeCents,
            Double netWorthChangePercent, Integer netWorthChangeDays, Budget.MonthPlan plan,
            TargetService.Progress targets, List<Card> cards,
            Instant lastSyncedAt, List<Problem> problems, boolean hasAccounts) {
    }

    public Home home(YearMonth month) {
        LocalDate today = LocalDate.now(zone);
        List<Account> visible = accounts.findByHiddenFalse();

        long assets = 0;
        long liabilities = 0;
        Map<String, long[]> groups = new LinkedHashMap<>();
        for (AccountType t : AccountType.values()) {
            groups.putIfAbsent(t.group(), new long[2]);
        }
        for (Account a : visible) {
            if (a.getBalanceCents() >= 0) {
                assets += a.getBalanceCents();
            } else {
                liabilities -= a.getBalanceCents();
            }
            long[] g = groups.get(a.getType().group());
            g[0] += a.getBalanceCents();
            g[1]++;
        }
        List<Group> groupList = new ArrayList<>();
        groups.forEach((name, v) -> {
            if (v[1] > 0) {
                groupList.add(new Group(name, v[0], (int) v[1]));
            }
        });

        NavigableMap<LocalDate, Long> daily = netWorth.daily(today.minusMonths(HISTORY_MONTHS), today);
        List<Point> history = new ArrayList<>();
        LocalDate next = null;
        for (Map.Entry<LocalDate, Long> e : daily.entrySet()) {
            // Weekly points for the six-month chart on Accounts, plus today.
            if (next == null || !e.getKey().isBefore(next) || e.getKey().equals(today)) {
                history.add(new Point(e.getKey(), e.getValue()));
                next = e.getKey().plusWeeks(1);
            }
        }
        List<Point> spark = daily.tailMap(today.minusDays(SPARK_DAYS), true).entrySet().stream()
                .map(e -> new Point(e.getKey(), e.getValue())).toList();
        Long change = null;
        Double changePct = null;
        Integer changeDays = null;
        if (spark.size() >= 2 && spark.get(0).day().isBefore(today.minusDays(SPARK_DAYS / 2))) {
            long start = spark.get(0).cents();
            change = spark.get(spark.size() - 1).cents() - start;
            changePct = start == 0 ? null : 100.0 * change / Math.abs(start);
            changeDays = (int) ChronoUnit.DAYS.between(spark.get(0).day(), today);
        }

        FlowService.Breakdown b = flow.month(month);
        List<Card> cards = visible.stream()
                .filter(a -> a.getType() == AccountType.CREDIT_CARD)
                .sorted(Comparator.comparingLong(Account::getBalanceCents))
                .map(a -> new Card(a.getId(), a.getName(), a.getInstitution(), a.getMask(), a.getBalanceCents(),
                        a.getCreditLimitCents(),
                        a.getCreditLimitCents() == null || a.getCreditLimitCents() <= 0 ? null
                                : (double) -a.getBalanceCents() / a.getCreditLimitCents(),
                        a.getPaymentDueDate(), a.getMinimumPaymentCents(), a.getStatementBalanceCents()))
                .toList();

        Instant lastSynced = null;
        List<Problem> problems = new ArrayList<>();
        for (Connection c : connections.findByProviderNot(ProviderType.MANUAL)) {
            if (c.getLastSyncedAt() != null && (lastSynced == null || c.getLastSyncedAt().isAfter(lastSynced))) {
                lastSynced = c.getLastSyncedAt();
            }
            if (c.getStatus() != ConnectionStatus.OK) {
                problems.add(new Problem(c.getId(), c.getDisplayName(), c.getStatus().name(), c.getLastError()));
            }
        }

        return new Home(month.toString(), assets - liabilities, assets, liabilities, groupList, history, spark,
                change, changePct, changeDays, budget.plan(month, b), targets.progress(month), cards, lastSynced, problems,
                !visible.isEmpty());
    }
}
