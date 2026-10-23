package cloud.gehan.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryRole;
import cloud.gehan.finance.model.CategoryRule;
import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.Paystub;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.service.MerchantLogos;
import cloud.gehan.finance.service.ReferenceData;

/** What the API sends. Entities never go over the wire directly; secrets live on them. */
public final class Views {

    private Views() {
    }

    public record TxnView(Long id, LocalDate day, long amountCents, String description, String merchant,
            Long accountId, String accountName, String accountType,
            Long categoryId, String categoryName, String categoryIcon, String categoryKind, String categorySource,
            boolean pending, boolean paired, String notes, boolean manual, boolean reviewed, boolean needsCategory,
            boolean oneOff, String logo) {

        public static TxnView of(Transaction t) {
            Category c = t.getCategory();
            Account a = t.getAccount();
            return new TxnView(t.getId(), t.getDay(), t.getAmountCents(), t.getDescription(), t.getMerchant(),
                    a.getId(), a.getName(), a.getType().name(),
                    c == null ? null : c.getId(), c == null ? null : c.getName(), c == null ? null : c.getIcon(),
                    c == null ? null : c.getKind().name(),
                    t.getCategorySource() == null ? null : t.getCategorySource().name(),
                    t.isPending(), t.getPairedTxnId() != null, t.getNotes(), t.isEditable(), t.isReviewed(),
                    c == null || c.hasRole(CategoryRole.UNCATEGORIZED), t.isOneOff(),
                    MerchantLogos.domainFor(t.getMerchant(), t.getDescription()));
        }
    }

    public record AccountView(Long id, String name, String institution, String mask, String type, String group,
            long balanceCents, Long availableCents, Long creditLimitCents, Long statementBalanceCents,
            Long minimumPaymentCents, LocalDate paymentDueDate, Double aprPercent, boolean hidden, boolean manual,
            Long connectionId, String provider, Instant balanceUpdatedAt, String logo) {

        public static AccountView of(Account a) {
            Connection c = a.getConnection();
            return new AccountView(a.getId(), a.getName(), a.getInstitution(), a.getMask(), a.getType().name(),
                    a.getType().group(), a.getBalanceCents(), a.getAvailableCents(), a.getCreditLimitCents(),
                    a.getStatementBalanceCents(), a.getMinimumPaymentCents(), a.getPaymentDueDate(),
                    a.getAprPercent(), a.isHidden(), a.isManual(),
                    c == null ? null : c.getId(), c == null ? "MANUAL" : c.getProvider().name(),
                    a.getBalanceUpdatedAt(), MerchantLogos.domainFor(a.getInstitution(), a.getName()));
        }
    }

    public record ConnectionView(Long id, String provider, String displayName, String status, String lastError,
            Instant lastSyncedAt, int accountCount, String externalId) {

        // externalId is the provider's id for the login; it is not a credential.
        public static ConnectionView of(Connection c, int accountCount) {
            return new ConnectionView(c.getId(), c.getProvider().name(), c.getDisplayName(), c.getStatus().name(),
                    c.getLastError(), c.getLastSyncedAt(), accountCount, c.getExternalId());
        }
    }

    /**
     * @param roles the jobs the app gives it (see CategoryRole)
     * @param jobs the same, in words: what the app files here on its own
     */
    public record CategoryView(Long id, String name, String kind, String icon, String color, int sortOrder,
            boolean earnedIncome, boolean fixedCost, List<String> roles, List<String> jobs) {
        public static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getName(), c.getKind().name(), c.getIcon(), c.getColor(), c.getSortOrder(),
                    c.isEarnedIncome(), c.isFixedCost(), c.getRoles().stream().map(Enum::name).toList(),
                    c.getRoles().stream().map(CategoryRole::job).toList());
        }
    }

    public record RuleView(Long id, String pattern, Long categoryId, String categoryName, String categoryIcon,
            String sign, String accountType, int priority, boolean builtIn) {
        public static RuleView of(CategoryRule r) {
            return new RuleView(r.getId(), r.getPattern(), r.getCategory().getId(), r.getCategory().getName(),
                    r.getCategory().getIcon(), r.getSign().name(),
                    r.getAccountType() == null ? null : r.getAccountType().name(), r.getPriority(),
                    r.getPriority() < ReferenceData.USER_PRIORITY);
        }
    }

    public record PaystubView(Long id, String employer, String matchPattern, String frequency, long grossCents,
            long federalTaxCents, long stateTaxCents, long ficaCents, long retirementCents, long benefitsCents,
            long otherDeductionsCents, long employerMatchCents, long netCents, boolean active) {
        public static PaystubView of(Paystub p) {
            return new PaystubView(p.getId(), p.getEmployer(), p.getMatchPattern(), p.getFrequency().name(),
                    p.getGrossCents(), p.getFederalTaxCents(), p.getStateTaxCents(), p.getFicaCents(),
                    p.getRetirementCents(), p.getBenefitsCents(), p.getOtherDeductionsCents(),
                    p.getEmployerMatchCents(), p.netCents(), p.isActive());
        }
    }
}
