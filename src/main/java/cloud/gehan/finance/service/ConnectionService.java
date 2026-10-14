package cloud.gehan.finance.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ProviderType;
import cloud.gehan.finance.model.SavingsGoal;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.provider.TellerClient;
import cloud.gehan.finance.provider.ProviderException;
import cloud.gehan.finance.provider.SimpleFinClient;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.BalanceSnapshotRepository;
import cloud.gehan.finance.repo.ConnectionRepository;
import cloud.gehan.finance.repo.HoldingRepository;
import cloud.gehan.finance.repo.HoldingTradeRepository;
import cloud.gehan.finance.repo.RecurringBuyRepository;
import cloud.gehan.finance.repo.SavingsGoalRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.security.SecretCipher;

@Service
public class ConnectionService {

    private final ConnectionRepository connections;
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final BalanceSnapshotRepository snapshots;
    private final SavingsGoalRepository goals;
    private final HoldingRepository holdings;
    private final HoldingTradeRepository trades;
    private final RecurringBuyRepository recurring;
    private final SimpleFinClient simplefin;
    private final TellerClient teller;
    private final SecretCipher cipher;
    private final SyncService sync;
    private final TransactionTemplate tx;

    public ConnectionService(ConnectionRepository connections, AccountRepository accounts, TransactionRepository txns,
            BalanceSnapshotRepository snapshots, SavingsGoalRepository goals, HoldingRepository holdings,
            HoldingTradeRepository trades, RecurringBuyRepository recurring, SimpleFinClient simplefin,
            TellerClient teller, SecretCipher cipher, SyncService sync, TransactionTemplate tx) {
        this.connections = connections;
        this.accounts = accounts;
        this.txns = txns;
        this.snapshots = snapshots;
        this.goals = goals;
        this.holdings = holdings;
        this.trades = trades;
        this.recurring = recurring;
        this.simplefin = simplefin;
        this.teller = teller;
        this.cipher = cipher;
        this.sync = sync;
        this.tx = tx;
    }

    public record Added(Long connectionId, String syncError) {
    }

    public Added addSimpleFin(String setupToken) {
        String accessUrl = simplefin.claim(setupToken);
        Connection saved = tx.execute(s -> {
            Connection c = new Connection(ProviderType.SIMPLEFIN, "SimpleFIN Bridge");
            c.setSecretEncrypted(cipher.encrypt(accessUrl));
            return connections.save(c);
        });
        return new Added(saved.getId(), sync.syncOne(saved.getId()));
    }

    /**
     * Stores what Teller Connect handed the browser. Reconnecting the same bank
     * login (same enrollment) replaces the token rather than duplicating accounts.
     */
    public Added addTeller(String accessToken, String enrollmentId, String institutionName) {
        if (!teller.configured()) {
            throw new ProviderException("Teller isn't set up on the server: set TELLER_APPLICATION_ID.", false);
        }
        Connection saved = tx.execute(s -> {
            Connection c = (enrollmentId == null ? Optional.<Connection>empty()
                    : connections.findByProviderAndExternalId(ProviderType.TELLER, enrollmentId))
                    .orElseGet(() -> new Connection(ProviderType.TELLER,
                            institutionName == null || institutionName.isBlank() ? "Bank" : institutionName));
            c.setExternalId(enrollmentId);
            c.setSecretEncrypted(cipher.encrypt(accessToken));
            return connections.save(c);
        });
        return new Added(saved.getId(), sync.syncOne(saved.getId()));
    }

    /** Deletes every account, transaction and balance a connection brought. */
    public void delete(Long connectionId) {
        Connection c = connections.findById(connectionId).orElseThrow();
        tx.executeWithoutResult(s -> {
            for (Account a : accounts.findByConnection(c)) {
                deleteAccountData(a);
            }
            connections.delete(c);
        });
    }

    /** Removes an account and everything hanging off it. Call inside a transaction. */
    public void deleteAccountData(Account a) {
        for (SavingsGoal g : goals.findByAccount(a)) {
            g.setAccount(null);
        }
        List<Transaction> theirs = new ArrayList<>(txns.findAll((root, q, cb) -> cb.equal(root.get("account"), a)));
        sync.delete(theirs);
        snapshots.deleteByAccount(a);
        recurring.deleteByAccount(a);
        trades.deleteByAccount(a);
        holdings.deleteByAccount(a);
        accounts.delete(a);
    }
}
