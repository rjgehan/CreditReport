package cloud.gehan.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ConnectionStatus;
import cloud.gehan.finance.model.ProviderType;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.provider.FetchResult;
import cloud.gehan.finance.provider.FetchResult.AccountData;
import cloud.gehan.finance.provider.FetchResult.LiabilityData;
import cloud.gehan.finance.provider.FetchResult.TxnData;
import cloud.gehan.finance.provider.ProviderException;
import cloud.gehan.finance.provider.SimpleFinClient;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.ConnectionRepository;
import cloud.gehan.finance.repo.TransactionRepository;
import cloud.gehan.finance.security.SecretCipher;

/**
 * Pulls every connection and folds the result into the database.
 *
 * <p>The network call happens outside any transaction: there is one database
 * connection (SQLite), and holding it through a 30-second bank round trip would
 * stall every page load behind the sync.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** How far back a first SimpleFIN pull reaches. The bridge caps history around here anyway. */
    static final int SIMPLEFIN_FIRST_DAYS = 90;
    /** Re-read this much before the last sync, so late-posting transactions are caught. */
    static final int SIMPLEFIN_OVERLAP_DAYS = 14;

    private final ConnectionRepository connections;
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final SimpleFinClient simplefin;
    private final SecretCipher cipher;
    private final Categorizer categorizer;
    private final TransferMatcher matcher;
    private final Balances balances;
    private final TransactionTemplate tx;
    private final ZoneId zone;

    public SyncService(ConnectionRepository connections, AccountRepository accounts, TransactionRepository txns,
            SimpleFinClient simplefin, SecretCipher cipher, Categorizer categorizer,
            TransferMatcher matcher, Balances balances, TransactionTemplate tx, AppProperties props) {
        this.connections = connections;
        this.accounts = accounts;
        this.txns = txns;
        this.simplefin = simplefin;
        this.cipher = cipher;
        this.categorizer = categorizer;
        this.matcher = matcher;
        this.balances = balances;
        this.tx = tx;
        this.zone = props.zoneId();
    }

    @Scheduled(cron = "${app.sync-cron}", zone = "${app.zone}")
    public void scheduled() {
        List<String> errors = syncAll();
        if (!errors.isEmpty()) {
            log.warn("Scheduled sync finished with {} problem(s): {}", errors.size(), errors);
        }
    }

    /** @return one message per connection that failed */
    public synchronized List<String> syncAll() {
        List<String> errors = new ArrayList<>();
        for (Connection c : connections.findByProviderNot(ProviderType.MANUAL)) {
            String error = syncOne(c.getId());
            if (error != null) {
                errors.add(c.getDisplayName() + ": " + error);
            }
        }
        return errors;
    }

    /** @return null on success, otherwise a message fit for the UI */
    public String syncOne(Long connectionId) {
        Connection c = connections.findById(connectionId).orElseThrow();
        try {
            FetchResult result = switch (c.getProvider()) {
                case SIMPLEFIN -> simplefin.fetch(cipher.decrypt(c.getSecretEncrypted()), simpleFinSince(c));
                case MANUAL -> null;
            };
            if (result == null) {
                return null;
            }
            tx.executeWithoutResult(s -> apply(connectionId, result));
            log.info("Synced {}: {} accounts, {} transactions", c.getDisplayName(),
                    result.accounts().size(), result.transactions().size());
            return null;
        } catch (ProviderException e) {
            log.warn("Sync of {} failed: {}", c.getDisplayName(), e.getMessage());
            tx.executeWithoutResult(s -> connections.findById(connectionId).ifPresent(conn -> {
                conn.setStatus(e.needsReauth() ? ConnectionStatus.NEEDS_REAUTH : ConnectionStatus.ERROR);
                conn.setLastError(e.getMessage());
            }));
            return e.getMessage();
        } catch (RuntimeException e) {
            log.error("Sync of {} failed unexpectedly", c.getDisplayName(), e);
            tx.executeWithoutResult(s -> connections.findById(connectionId).ifPresent(conn -> {
                conn.setStatus(ConnectionStatus.ERROR);
                conn.setLastError("Unexpected error; see the server log.");
            }));
            return "Unexpected error; see the server log.";
        }
    }

    private LocalDate simpleFinSince(Connection c) {
        LocalDate today = LocalDate.now(zone);
        if (c.getLastSyncedAt() == null) {
            return today.minusDays(SIMPLEFIN_FIRST_DAYS);
        }
        return c.getLastSyncedAt().atZone(zone).toLocalDate().minusDays(SIMPLEFIN_OVERLAP_DAYS);
    }

    void apply(Long connectionId, FetchResult result) {
        Connection c = connections.findById(connectionId).orElseThrow();
        LocalDate today = LocalDate.now(zone);

        Map<String, Account> byExternalId = new HashMap<>();
        for (AccountData a : result.accounts()) {
            Account account = accounts.findByConnectionAndExternalId(c, a.externalId()).orElseGet(() -> {
                Account created = new Account(c, a.externalId(), a.name(), a.type());
                created.setInstitution(a.institution());
                return created;
            });
            // Name and type are only set on creation; after that they are yours to edit.
            if (account.getInstitution() == null) {
                account.setInstitution(a.institution());
            }
            if (a.mask() != null) {
                account.setMask(a.mask());
            }
            account.setCurrency(a.currency());
            account.setAvailableCents(a.availableCents());
            if (a.creditLimitCents() != null) {
                account.setCreditLimitCents(a.creditLimitCents());
            }
            account = accounts.save(account);
            balances.record(account, today, a.balanceCents(), true);
            byExternalId.put(a.externalId(), account);
        }
        // Accounts the provider didn't list this time (closed, or temporarily
        // missing) still resolve, so their transactions land in the right place.
        for (Account existing : accounts.findByConnection(c)) {
            byExternalId.putIfAbsent(existing.getExternalId(), existing);
        }

        for (LiabilityData l : result.liabilities()) {
            Account account = byExternalId.get(l.accountExternalId());
            if (account != null) {
                account.setStatementBalanceCents(l.statementBalanceCents());
                account.setMinimumPaymentCents(l.minimumPaymentCents());
                account.setPaymentDueDate(l.paymentDueDate());
                account.setAprPercent(l.aprPercent());
            }
        }

        List<Transaction> touched = new ArrayList<>();
        List<Transaction> inserted = new ArrayList<>();
        Map<Account, Set<String>> seenPending = new HashMap<>();
        LocalDate earliest = today;
        for (TxnData t : result.transactions()) {
            Account account = byExternalId.get(t.accountExternalId());
            if (account == null) {
                continue;
            }
            Transaction txn = txns.findByAccountAndExternalId(account, t.externalId())
                    .orElseGet(() -> new Transaction(account, t.externalId(), t.day(), t.amountCents(), t.description()));
            boolean isNew = txn.getId() == null;
            boolean changed = isNew
                    || txn.getAmountCents() != t.amountCents()
                    || !Objects.equals(txn.getDescription(), t.description())
                    || !Objects.equals(txn.getProviderCategory(), t.providerCategory());
            txn.setDay(t.day());
            txn.setAmountCents(t.amountCents());
            txn.setDescription(t.description());
            txn.setMerchant(t.merchant());
            txn.setPending(t.pending());
            txn.setProviderCategory(t.providerCategory());
            txn = txns.save(txn);
            if (changed) {
                touched.add(txn);
            }
            if (isNew && !t.pending()) {
                inserted.add(txn);
            }
            if (t.pending()) {
                seenPending.computeIfAbsent(account, k -> new HashSet<>()).add(t.externalId());
            }
            if (t.day().isBefore(earliest)) {
                earliest = t.day();
            }
        }

        List<Transaction> doomed = new ArrayList<>();
        if (!result.removedTransactionIds().isEmpty()) {
            for (Account account : new HashSet<>(byExternalId.values())) {
                doomed.addAll(txns.findByAccountAndExternalIdIn(account, result.removedTransactionIds()));
            }
        }
        if (result.pendingAuthoritative()) {
            // SimpleFIN gives a pending transaction one id and the posted one
            // another. A stored pending row the provider no longer lists has posted
            // (and arrived above under its new id) or fallen off.
            for (AccountData a : result.accounts()) {
                Account account = byExternalId.get(a.externalId());
                Set<String> stillPending = seenPending.getOrDefault(account, Set.of());
                for (Transaction p : txns.findByAccountAndPendingTrue(account)) {
                    if (!stillPending.contains(p.getExternalId())) {
                        doomed.add(p);
                    }
                }
            }
        }
        carryOver(doomed, inserted);
        delete(doomed);

        categorizer.categorize(touched);
        Categorizer.Context ctx = categorizer.context();
        matcher.match(txns.findByDayGreaterThanEqual(earliest.minusDays(TransferMatcher.MAX_DAYS_APART + 1)), ctx);

        c.setSyncCursor(result.nextCursor());
        c.setLastSyncedAt(Instant.now());
        c.setStatus(ConnectionStatus.OK);
        c.setLastError(result.warnings().isEmpty() ? null : String.join("; ", result.warnings()));
    }

    /**
     * A pending charge comes back posted under a new id (often with a tip added). What you
     * did to the pending one (checked it off, picked its category, wrote a note) moves to
     * the posted one: same account, same direction, within a few days and a quarter of the amount.
     */
    static void carryOver(List<Transaction> doomed, List<Transaction> posted) {
        Set<Transaction> claimed = new HashSet<>();
        for (Transaction p : doomed) {
            boolean yours = p.isReviewed() || p.getCategorySource() == CategorySource.MANUAL || p.getNotes() != null;
            if (!p.isPending() || !yours) {
                continue;
            }
            Transaction best = null;
            long bestScore = Long.MAX_VALUE;
            for (Transaction n : posted) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(p.getDay(), n.getDay());
                long diff = Math.abs(n.getAmountCents() - p.getAmountCents());
                boolean close = Objects.equals(n.getAccount().getId(), p.getAccount().getId()) && !claimed.contains(n)
                        && Long.signum(n.getAmountCents()) == Long.signum(p.getAmountCents())
                        && days >= -1 && days <= 6 && diff <= Math.abs(p.getAmountCents()) / 4;
                long score = diff * 10 + Math.abs(days);
                if (close && score < bestScore) {
                    best = n;
                    bestScore = score;
                }
            }
            if (best == null) {
                continue;
            }
            claimed.add(best);
            if (p.getCategorySource() == CategorySource.MANUAL && p.getCategory() != null) {
                best.setCategory(p.getCategory());
                best.setCategorySource(CategorySource.MANUAL);
            }
            if (p.isReviewed()) {
                best.setReviewed(true);
            }
            if (best.getNotes() == null && p.getNotes() != null) {
                best.setNotes(p.getNotes());
            }
        }
    }

    /** Deletes transactions and lets any transfer partner fall back to its own category. */
    public void delete(List<Transaction> doomed) {
        if (doomed.isEmpty()) {
            return;
        }
        List<Long> ids = doomed.stream().map(Transaction::getId).toList();
        List<Transaction> partners = new ArrayList<>();
        for (Transaction t : doomed) {
            if (t.getPairedTxnId() != null && !ids.contains(t.getPairedTxnId())) {
                txns.findById(t.getPairedTxnId()).ifPresent(partners::add);
            }
        }
        txns.deleteAll(doomed);
        for (Transaction p : partners) {
            p.setPairedTxnId(null);
            if (p.getCategorySource() == CategorySource.TRANSFER_MATCH) {
                p.setCategorySource(CategorySource.DEFAULT);
            }
        }
        categorizer.categorize(partners);
    }
}
