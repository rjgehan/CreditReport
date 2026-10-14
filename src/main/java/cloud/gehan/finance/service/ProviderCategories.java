package cloud.gehan.finance.service;

/**
 * A provider's own category label, as stored on the transaction, to this app's
 * category name. Null means "no opinion": the categorizer falls through to the
 * account-type default. Your rules always run first.
 */
final class ProviderCategories {

    private ProviderCategories() {
    }

    static String map(String providerCategory) {
        if (providerCategory == null || providerCategory.isBlank()) {
            return null;
        }
        if (providerCategory.startsWith("teller:")) {
            return teller(providerCategory.substring("teller:".length()));
        }
        return null;
    }

    /** Teller's details.category values (https://teller.io/docs/api/account/transactions). */
    static String teller(String category) {
        return switch (category) {
            case "accommodation" -> "Travel";
            case "bar", "dining" -> "Dining";
            case "charity" -> "Gifts & Donations";
            case "clothing", "electronics", "shopping", "office", "sport" -> "Shopping";
            case "education" -> "Education";
            case "entertainment" -> "Entertainment";
            case "fuel" -> "Gas";
            case "groceries" -> "Groceries";
            case "health" -> "Health";
            case "home" -> "Housing";
            case "income" -> ReferenceData.OTHER_INCOME;
            case "insurance" -> "Insurance";
            case "investment" -> ReferenceData.INVESTING;
            case "loan" -> "Loans";
            case "phone", "utilities" -> "Utilities";
            case "software" -> "Subscriptions";
            case "tax" -> "Taxes";
            case "transport", "transportation" -> "Transportation";
            // "general", "service", "advertising" say nothing useful.
            default -> null;
        };
    }
}
