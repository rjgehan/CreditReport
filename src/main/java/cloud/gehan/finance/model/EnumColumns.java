package cloud.gehan.finance.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores every enum as its plain name in a text column.
 *
 * <p>Hibernate's own enum mapping adds a CHECK constraint listing the values that
 * existed when the table was created, and SQLite can't alter a constraint. A
 * later version adding a value (as CategorySource.AI did) would then fail every
 * insert on an existing database. A converter writes the same text with no
 * constraint, so new values just work.
 */
public final class EnumColumns {

    private EnumColumns() {
    }

    abstract static class ByName<E extends Enum<E>> implements AttributeConverter<E, String> {
        private final Class<E> type;

        ByName(Class<E> type) {
            this.type = type;
        }

        @Override
        public String convertToDatabaseColumn(E value) {
            return value == null ? null : value.name();
        }

        @Override
        public E convertToEntityAttribute(String column) {
            return column == null ? null : Enum.valueOf(type, column);
        }
    }

    @Converter(autoApply = true)
    public static class AccountTypes extends ByName<AccountType> {
        public AccountTypes() { super(AccountType.class); }
    }

    @Converter(autoApply = true)
    public static class AmountSigns extends ByName<AmountSign> {
        public AmountSigns() { super(AmountSign.class); }
    }

    @Converter(autoApply = true)
    public static class BuyCadences extends ByName<BuyCadence> {
        public BuyCadences() { super(BuyCadence.class); }
    }

    @Converter(autoApply = true)
    public static class Cadences extends ByName<Cadence> {
        public Cadences() { super(Cadence.class); }
    }

    @Converter(autoApply = true)
    public static class RecurringKinds extends ByName<RecurringKind> {
        public RecurringKinds() { super(RecurringKind.class); }
    }

    @Converter(autoApply = true)
    public static class CategoryKinds extends ByName<CategoryKind> {
        public CategoryKinds() { super(CategoryKind.class); }
    }

    @Converter(autoApply = true)
    public static class CategorySources extends ByName<CategorySource> {
        public CategorySources() { super(CategorySource.class); }
    }

    @Converter(autoApply = true)
    public static class ConnectionStatuses extends ByName<ConnectionStatus> {
        public ConnectionStatuses() { super(ConnectionStatus.class); }
    }

    @Converter(autoApply = true)
    public static class GoalTypes extends ByName<GoalType> {
        public GoalTypes() { super(GoalType.class); }
    }

    @Converter(autoApply = true)
    public static class PayFrequencies extends ByName<PayFrequency> {
        public PayFrequencies() { super(PayFrequency.class); }
    }

    @Converter(autoApply = true)
    public static class ProviderTypes extends ByName<ProviderType> {
        public ProviderTypes() { super(ProviderType.class); }
    }

    @Converter(autoApply = true)
    public static class TradeSources extends ByName<TradeSource> {
        public TradeSources() { super(TradeSource.class); }
    }
}
