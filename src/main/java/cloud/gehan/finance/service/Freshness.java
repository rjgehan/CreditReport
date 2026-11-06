package cloud.gehan.finance.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

/**
 * Whether the bank data is actually current. A SimpleFIN pull can succeed while a bank
 * behind it has stopped updating (it needs a fresh sign-in on the bridge): the pull
 * returns 200 with an error message, and that bank's balance-date stops moving. Left
 * unsaid, spending would seem to stop and free-to-spend would climb.
 */
@Service
@Transactional(readOnly = true)
public class Freshness {

    /** Banks update daily, weekends included; three days without anything is a broken feed. */
    static final Duration BEHIND_AFTER = Duration.ofDays(3);
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private final ConnectionRepository connections;
    private final AccountRepository accounts;
    private final ZoneId zone;

    public Freshness(ConnectionRepository connections, AccountRepository accounts, AppProperties props) {
        this.connections = connections;
        this.accounts = accounts;
        this.zone = props.zoneId();
    }

    /**
     * @param status NEEDS_REAUTH, ERROR (the pull failed), WARNING (it worked but SimpleFIN
     *        says a bank needs attention) or BEHIND (an account hasn't updated in days)
     * @param since BEHIND only: the last day it had new data
     */
    public record Problem(Long connectionId, String name, String status, String error, LocalDate since) {
        /** One line, for the sync toast. */
        public String line() {
            return switch (status) {
                case "BEHIND" -> name + ": nothing new since " + SHORT.format(since);
                case "WARNING" -> name + ": " + (error == null ? "a bank needs attention" : error);
                case "NEEDS_REAUTH" -> name + ": sign in again";
                default -> name + ": " + (error == null ? "sync failed" : error);
            };
        }
    }

    /** A spending account (checking, a card) that has had nothing new for days. */
    public record Behind(Account account, LocalDate since) {
    }

    /**
     * One entry per thing wrong, not per symptom: a connection that fails is one problem (its
     * accounts are behind because of it); a pull that worked with a bank's warning is named by
     * the account that stopped updating when there is one ("Chase card: nothing new since Nov 2"
     * says more than "a bank needs attention").
     */
    public List<Problem> problems() {
        List<Behind> behind = behind();
        List<Problem> out = new ArrayList<>();
        java.util.Set<Long> failing = new java.util.HashSet<>();
        for (Connection c : connections.findByProviderNot(ProviderType.MANUAL)) {
            boolean quiet = behind.stream().anyMatch(b -> b.account().getConnection().getId().equals(c.getId()));
            if (c.getStatus() != ConnectionStatus.OK) {
                failing.add(c.getId());
                out.add(new Problem(c.getId(), c.getDisplayName(), c.getStatus().name(), c.getLastError(), null));
            } else if (c.getLastError() != null && !c.getLastError().isBlank() && !quiet) {
                out.add(new Problem(c.getId(), c.getDisplayName(), "WARNING", c.getLastError(), null));
            }
        }
        for (Behind b : behind) {
            Account a = b.account();
            if (!failing.contains(a.getConnection().getId())) {
                out.add(new Problem(a.getConnection().getId(), label(a), "BEHIND", null, b.since()));
            }
        }
        return out;
    }

    /** Only the accounts everyday spending goes through: a quiet savings account isn't a problem. */
    public List<Behind> behind() {
        Instant cutoff = Instant.now().minus(BEHIND_AFTER);
        List<Behind> out = new ArrayList<>();
        for (Account a : accounts.findByHiddenFalse()) {
            boolean spending = a.getType() == AccountType.CHECKING || a.getType() == AccountType.CREDIT_CARD;
            if (spending && !a.isManual() && a.getDataAsOf() != null && a.getDataAsOf().isBefore(cutoff)) {
                out.add(new Behind(a, a.getDataAsOf().atZone(zone).toLocalDate()));
            }
        }
        return out;
    }

    static String label(Account a) {
        String institution = a.getInstitution();
        return institution == null || institution.isBlank() || a.getName().toLowerCase(Locale.ROOT)
                .contains(institution.toLowerCase(Locale.ROOT)) ? a.getName() : institution + " " + a.getName();
    }
}
