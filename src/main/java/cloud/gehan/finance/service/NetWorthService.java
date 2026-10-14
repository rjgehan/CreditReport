package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.BalanceSnapshot;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.BalanceSnapshotRepository;

/**
 * Net worth on any past day, rebuilt from the daily balance snapshots. Each
 * account's last known balance carries forward over days nobody recorded it, and
 * today is always the live balance.
 */
@Service
@Transactional(readOnly = true)
public class NetWorthService {

    private final AccountRepository accounts;
    private final BalanceSnapshotRepository snapshots;
    private final ZoneId zone;

    public NetWorthService(AccountRepository accounts, BalanceSnapshotRepository snapshots, AppProperties props) {
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.zone = props.zoneId();
    }

    /** Net worth at the end of each day from {@code from} to {@code to} (capped at today). Days before any data are absent. */
    public NavigableMap<LocalDate, Long> daily(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to.isAfter(today) ? today : to;
        List<Account> visible = accounts.findByHiddenFalse();
        Map<Long, TreeMap<LocalDate, Long>> byAccount = new HashMap<>();
        for (Account a : visible) {
            TreeMap<LocalDate, Long> series = new TreeMap<>();
            snapshots.findTopByAccountAndDayLessThanOrderByDayDesc(a, from)
                    .ifPresent(s -> series.put(s.getDay(), s.getBalanceCents()));
            byAccount.put(a.getId(), series);
        }
        for (BalanceSnapshot s : snapshots.findByDayGreaterThanEqualOrderByDayAsc(from)) {
            TreeMap<LocalDate, Long> series = byAccount.get(s.getAccount().getId());
            if (series != null && !s.getDay().isAfter(end)) {
                series.put(s.getDay(), s.getBalanceCents());
            }
        }
        if (!end.isBefore(today)) {
            for (Account a : visible) {
                byAccount.get(a.getId()).put(today, a.getBalanceCents());
            }
        }

        // Tracking starts on the first day any account has a balance. From then on, an
        // account connected later counts at its first known balance on earlier days
        // too: linking a bank moves money into view, it doesn't make you richer.
        LocalDate tracked = byAccount.values().stream().filter(s -> !s.isEmpty()).map(TreeMap::firstKey)
                .min(LocalDate::compareTo).orElse(null);
        NavigableMap<LocalDate, Long> out = new TreeMap<>();
        if (tracked == null) {
            return out;
        }
        for (LocalDate day = from.isBefore(tracked) ? tracked : from; !day.isAfter(end); day = day.plusDays(1)) {
            long total = 0;
            for (TreeMap<LocalDate, Long> series : byAccount.values()) {
                Map.Entry<LocalDate, Long> e = series.floorEntry(day);
                if (e == null && !series.isEmpty()) {
                    e = series.firstEntry();
                }
                if (e != null) {
                    total += e.getValue();
                }
            }
            out.put(day, total);
        }
        return out;
    }

    /** Net worth at the end of {@code day}, or null if nothing was tracked yet. */
    public Long on(LocalDate day) {
        NavigableMap<LocalDate, Long> one = daily(day, day);
        return one.isEmpty() ? null : one.firstEntry().getValue();
    }
}
