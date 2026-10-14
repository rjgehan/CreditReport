package cloud.gehan.finance.model;

public enum AmountSign {
    ANY,
    /** Money arriving: deposits, refunds, card payments seen from the card. */
    IN,
    /** Money leaving: purchases, bills, transfers out. */
    OUT;

    public boolean matches(long amountCents) {
        return switch (this) {
            case ANY -> true;
            case IN -> amountCents > 0;
            case OUT -> amountCents < 0;
        };
    }
}
