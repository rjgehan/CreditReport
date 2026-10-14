package cloud.gehan.finance.provider;

import java.util.Locale;
import java.util.regex.Pattern;

import cloud.gehan.finance.model.AccountType;

/**
 * SimpleFIN says nothing about what kind of account something is, so this reads
 * the name. It only decides the type when an account first appears; after that
 * the type is yours to change on the Accounts page and sync leaves it alone.
 */
public final class AccountTypeGuesser {

    private static final Pattern RETIREMENT = Pattern.compile(
            "\\b(401 ?\\(?k\\)?|403 ?\\(?b\\)?|457|ira|roth|retirement|pension|tsp|sep|pcs)\\b");
    private static final Pattern BROKERAGE = Pattern.compile(
            "\\b(brokerage|individual|invest\\w*|webull|robinhood|stocks?|margin|joint wros|crypto)\\b");
    private static final Pattern CREDIT = Pattern.compile(
            "\\b(credit|card|visa|mastercard|amex|american express|discover|sapphire|freedom|venture|"
                    + "quicksilver|platinum|gold|blue cash|double cash|custom cash|savor|apple card)\\b");
    private static final Pattern SAVINGS = Pattern.compile("\\b(savings?|money market|mma|high yield|cd|certificate)\\b");
    private static final Pattern LOAN = Pattern.compile("\\b(loan|mortgage|auto|student|heloc)\\b");
    private static final Pattern CHECKING = Pattern.compile("\\b(checking|chequing|spend|debit)\\b");

    private AccountTypeGuesser() {
    }

    public static AccountType guess(String name, String institution, long balanceCents) {
        String text = ((name == null ? "" : name) + " " + (institution == null ? "" : institution)).toLowerCase(Locale.ROOT);
        if (RETIREMENT.matcher(text).find()) {
            return AccountType.RETIREMENT;
        }
        // Savings and checking before credit: "Navy Federal Credit Union Checking" is a bank account.
        if (SAVINGS.matcher(text).find()) {
            return AccountType.SAVINGS;
        }
        if (CHECKING.matcher(text).find()) {
            return AccountType.CHECKING;
        }
        if (CREDIT.matcher(text).find()) {
            return AccountType.CREDIT_CARD;
        }
        if (BROKERAGE.matcher(text).find()) {
            return AccountType.BROKERAGE;
        }
        if (LOAN.matcher(text).find()) {
            return AccountType.LOAN;
        }
        // A bank account below zero is unusual; a card below zero is normal.
        return balanceCents < 0 ? AccountType.CREDIT_CARD : AccountType.CHECKING;
    }
}
