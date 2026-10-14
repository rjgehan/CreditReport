package cloud.gehan.finance.provider;

import java.time.LocalDate;
import java.util.List;

import cloud.gehan.finance.model.AccountType;

/**
 * Everything one pull from a provider produced, already normalised: amounts in
 * cents and signed from your point of view (money out negative, debt negative).
 *
 * @param removedTransactionIds provider ids known to have been deleted (none of the
 *        current providers say; see {@code pendingAuthoritative})
 * @param pendingAuthoritative true when the batch lists every pending transaction
 *        the provider knows about, so any stored pending row not in it has since
 *        posted under a new id or dropped, and should go
 */
public record FetchResult(
        List<AccountData> accounts,
        List<TxnData> transactions,
        List<String> removedTransactionIds,
        List<LiabilityData> liabilities,
        String nextCursor,
        boolean pendingAuthoritative,
        List<String> warnings) {

    public record AccountData(
            String externalId,
            String name,
            String institution,
            String mask,
            AccountType type,
            long balanceCents,
            Long availableCents,
            Long creditLimitCents,
            String currency) {
    }

    public record TxnData(
            String accountExternalId,
            String externalId,
            LocalDate day,
            long amountCents,
            String description,
            String merchant,
            boolean pending,
            String providerCategory) {
    }

    public record LiabilityData(
            String accountExternalId,
            Long statementBalanceCents,
            Long minimumPaymentCents,
            LocalDate paymentDueDate,
            Double aprPercent) {
    }
}
