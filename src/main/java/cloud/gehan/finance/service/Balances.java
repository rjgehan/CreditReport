package cloud.gehan.finance.service;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.BalanceSnapshot;
import cloud.gehan.finance.repo.BalanceSnapshotRepository;

@Component
public class Balances {

    private final BalanceSnapshotRepository snapshots;

    public Balances(BalanceSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** Sets the account's balance and records it as that day's snapshot. Call inside a transaction. */
    public void record(Account account, LocalDate day, long balanceCents, boolean isLatest) {
        if (isLatest) {
            account.setBalanceCents(balanceCents);
            account.setBalanceUpdatedAt(Instant.now());
        }
        BalanceSnapshot snapshot = snapshots.findByAccountAndDay(account, day)
                .orElseGet(() -> new BalanceSnapshot(account, day, balanceCents));
        snapshot.setBalanceCents(balanceCents);
        snapshots.save(snapshot);
    }

    /** The balance on or before 30 days ago, or null when the history doesn't reach back that far. */
    public Long monthAgo(cloud.gehan.finance.model.Account account, java.time.LocalDate today) {
        return snapshots.findTopByAccountAndDayLessThanEqualOrderByDayDesc(account, today.minusDays(30))
                .map(s -> s.getBalanceCents()).orElse(null);
    }
}
