package cloud.gehan.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.BalanceSnapshot;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ConnectionStatus;
import cloud.gehan.finance.model.ProviderType;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.BalanceSnapshotRepository;
import cloud.gehan.finance.repo.ConnectionRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.web.Views.TxnView;

/** Everything the Home screen shows, in one request. */
@Service
@Transactional(readOnly = true)
public class SummaryService {

    static final int HISTORY_MONTHS = 6;

    private final AccountRepository accounts;
    private final BalanceSnapshotRepository snapshots;
    private final ConnectionRepository connections;
    private final TransactionRepository txns;
    private final FlowService flow;
    private final TargetService targets;
    private final AppProperties props;
    private final ZoneId zone;

    public SummaryService(AccountRepository accounts, BalanceSnapshotRepository snapshots,
            ConnectionRepository connections, TransactionRepository txns, FlowService flow, TargetService targets,
            AppProperties props) {
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.connections = connections;
        this.txns = txns;
        this.flow = flow;
        this.targets = targets;
        this.props = props;
        this.zone = props.zoneId();
    }

    public record Point(LocalDate day, long cents) {
    }

    public record Group(String name, long cents, int count) {
    }

    public record MonthTotals(String month, long grossIncomeCents, long incomeCents, long spentCents,
            long savedCents, long leftoverCents, Double savingsRate) {
    }

    public record CategoryAmount(Long categoryId, String name, String icon, long cents) {
    }

    public record Card(Long id, String name, String institution, String mask, long balanceCents, Long limitCents,
            Double utilization, LocalDate dueDate, Long minimumPaymentCents, Long statementBalanceCents) {
    }

    public record Problem(Long connectionId, String name, String status, String error) {
    }

    public record Home(String month, boolean currentMonth, int dayOfMonth, int daysInMonth,
            long netWorthCents, long assetsCents, long liabilitiesCents, List<Group> groups,
            List<Point> netWorthHistory, MonthTotals totals, List<CategoryAmount> topSpending,
            TargetService.Progress targets, List<Card> cards, List<MonthTotals> trend, List<TxnView> recent,
            Instant lastSyncedAt, List<Problem> problems, boolean hasAccounts, boolean demo) {
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

        FlowService.Breakdown b = flow.month(month);
        List<CategoryAmount> top = b.expenses().entrySet().stream().limit(6)
                .map(e -> new CategoryAmount(e.getKey().categoryId(), e.getKey().name(), e.getKey().icon(), e.getValue()))
                .toList();

        List<MonthTotals> trend = new ArrayList<>();
        for (int i = HISTORY_MONTHS - 1; i >= 0; i--) {
            YearMonth m = month.minusMonths(i);
            trend.add(totals(m, i == 0 ? b : flow.month(m)));
        }

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

        boolean current = YearMonth.from(today).equals(month);
        return new Home(month.toString(), current, current ? today.getDayOfMonth() : month.lengthOfMonth(),
                month.lengthOfMonth(), assets - liabilities, assets, liabilities, groupList,
                netWorthHistory(visible, today), totals(month, b), top, targets.progress(month), cards, trend,
                txns.findTop10ByAccountHiddenFalseOrderByDayDescIdDesc().stream().map(TxnView::of).toList(),
                lastSynced, problems, !visible.isEmpty(), props.demoData());
    }

    private static MonthTotals totals(YearMonth m, FlowService.Breakdown b) {
        return new MonthTotals(m.toString(), b.grossIncome(), b.cashIncome(), b.spent(), b.saved(), b.leftover(),
                b.savingsRate());
    }

    /**
     * Net worth each week for the last six months, carrying each account's last
     * known balance forward over days nobody synced it.
     */
    List<Point> netWorthHistory(List<Account> visible, LocalDate today) {
        LocalDate start = today.minusMonths(HISTORY_MONTHS);
        Map<Long, TreeMap<LocalDate, Long>> byAccount = new HashMap<>();
        for (Account a : visible) {
            TreeMap<LocalDate, Long> series = new TreeMap<>();
            snapshots.findTopByAccountAndDayLessThanOrderByDayDesc(a, start)
                    .ifPresent(s -> series.put(s.getDay(), s.getBalanceCents()));
            byAccount.put(a.getId(), series);
        }
        for (BalanceSnapshot s : snapshots.findByDayGreaterThanEqualOrderByDayAsc(start)) {
            TreeMap<LocalDate, Long> series = byAccount.get(s.getAccount().getId());
            if (series != null) {
                series.put(s.getDay(), s.getBalanceCents());
            }
        }
        // Today is always the live balance, even if no snapshot has been written yet.
        for (Account a : visible) {
            byAccount.get(a.getId()).put(today, a.getBalanceCents());
        }

        List<Point> points = new ArrayList<>();
        LocalDate first = byAccount.values().stream()
                .filter(s -> !s.isEmpty()).map(TreeMap::firstKey).min(LocalDate::compareTo).orElse(today);
        LocalDate day = first.isAfter(start) ? first : start;
        while (!day.isAfter(today)) {
            points.add(new Point(day, worthOn(byAccount, day)));
            if (day.equals(today)) {
                break;
            }
            day = day.plusWeeks(1).isAfter(today) ? today : day.plusWeeks(1);
        }
        return points;
    }

    private static long worthOn(Map<Long, TreeMap<LocalDate, Long>> byAccount, LocalDate day) {
        long total = 0;
        for (TreeMap<LocalDate, Long> series : byAccount.values()) {
            Map.Entry<LocalDate, Long> e = series.floorEntry(day);
            if (e != null) {
                total += e.getValue();
            }
        }
        return total;
    }
}
