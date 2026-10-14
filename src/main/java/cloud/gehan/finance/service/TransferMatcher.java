package cloud.gehan.finance.service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryKind;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;

/**
 * Finds the two halves of a move between your own accounts: 500 dollars out of
 * checking on the 3rd and 500 into savings on the 4th. Unpaired, the first looks
 * like spending and the second like income. Paired, the checking side becomes
 * Savings (or Investing, Retirement, Credit Card Payment, depending on where the
 * money went) and the far side a plain Transfer.
 *
 * <p>Only rewrites a side whose category was a guess. A side you or a rule
 * categorised as real spending or income is never paired, which keeps a 50 dollar
 * grocery run from pairing with a 50 dollar Venmo that happened the same day.
 */
@Component
public class TransferMatcher {

    static final int MAX_DAYS_APART = 4;

    /** @return how many pairs were made */
    public int match(List<Transaction> candidates, Categorizer.Context ctx) {
        List<Transaction> unpaired = new ArrayList<>(candidates.stream()
                .filter(t -> t.getPairedTxnId() == null && t.getId() != null && pairable(t))
                .sorted(Comparator.comparing(Transaction::getDay).thenComparing(Transaction::getId))
                .toList());

        // Inflows by amount, so each outflow only looks at same-sized candidates.
        Map<Long, List<Transaction>> inflows = new HashMap<>();
        for (Transaction t : unpaired) {
            if (t.getAmountCents() > 0) {
                inflows.computeIfAbsent(t.getAmountCents(), k -> new ArrayList<>()).add(t);
            }
        }

        int pairs = 0;
        for (Transaction out : unpaired) {
            if (out.getAmountCents() >= 0 || out.getPairedTxnId() != null) {
                continue;
            }
            Transaction best = null;
            long bestGap = Long.MAX_VALUE;
            for (Transaction in : inflows.getOrDefault(-out.getAmountCents(), List.of())) {
                if (in.getPairedTxnId() != null || in.getAccount().getId().equals(out.getAccount().getId())) {
                    continue;
                }
                long gap = Math.abs(ChronoUnit.DAYS.between(out.getDay(), in.getDay()));
                if (gap <= MAX_DAYS_APART && gap < bestGap) {
                    best = in;
                    bestGap = gap;
                }
            }
            if (best != null) {
                pair(out, best, ctx);
                pairs++;
            }
        }
        return pairs;
    }

    /** A guess, or something already known to be a transfer. Never real spending or income. */
    static boolean pairable(Transaction t) {
        if (t.getCategorySource() == CategorySource.DEFAULT || t.getCategory() == null) {
            return true;
        }
        CategoryKind kind = t.getCategory().getKind();
        return kind == CategoryKind.TRANSFER || kind == CategoryKind.SAVINGS;
    }

    private void pair(Transaction out, Transaction in, Categorizer.Context ctx) {
        out.setPairedTxnId(in.getId());
        in.setPairedTxnId(out.getId());

        AccountType from = out.getAccount().getType();
        AccountType to = in.getAccount().getType();
        String outCategory;
        String inCategory;
        if (to.savingsLike() && !from.savingsLike()) {
            // Money put away. The checking side carries it so it is counted once.
            outCategory = savingsCategory(to);
            inCategory = ReferenceData.TRANSFER;
        } else if (from.savingsLike() && !to.savingsLike()) {
            // Money taken back out: a positive amount in a savings category,
            // which nets against what was saved this month.
            outCategory = ReferenceData.TRANSFER;
            inCategory = savingsCategory(from);
        } else if (to == AccountType.CREDIT_CARD) {
            outCategory = ReferenceData.CARD_PAYMENT;
            inCategory = ReferenceData.CARD_PAYMENT;
        } else {
            outCategory = ReferenceData.TRANSFER;
            inCategory = ReferenceData.TRANSFER;
        }
        assign(out, ctx.named(outCategory));
        assign(in, ctx.named(inCategory));
    }

    private static String savingsCategory(AccountType type) {
        return switch (type) {
            case BROKERAGE -> ReferenceData.INVESTING;
            case RETIREMENT -> ReferenceData.RETIREMENT;
            default -> ReferenceData.SAVINGS;
        };
    }

    /** Rules and you outrank the pairing; it only replaces guesses and generic transfers. */
    private static void assign(Transaction t, Category category) {
        if (category == null) {
            return;
        }
        boolean guess = t.getCategorySource() == CategorySource.DEFAULT;
        boolean genericTransfer = t.getCategorySource() == CategorySource.PROVIDER
                && t.getCategory() != null && t.getCategory().getKind() == CategoryKind.TRANSFER;
        if (guess || genericTransfer || t.getCategory() == null) {
            t.setCategory(category);
            t.setCategorySource(CategorySource.TRANSFER_MATCH);
        }
    }
}
