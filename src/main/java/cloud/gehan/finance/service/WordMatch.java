package cloud.gehan.finance.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A rule's words found in a transaction's description: whole words, any case,
 * and forgiving about the punctuation, dates and reference numbers between them.
 * "ACME INC" finds "ACME, INC. 1015 PAYROLL"; "RENT" still doesn't find "CURRENT",
 * and "ADP" doesn't find the letters inside a code like "4471ADP90X".
 *
 * <p>Banks pad descriptions with things that change from one payment to the next,
 * so a pattern taken from one deposit, or typed from memory, rarely matches the
 * next one character for character.
 */
public final class WordMatch {

    /** Letters and light punctuation only. Anything with digits or symbols (7-ELEVEN, SQ *) matches as typed. */
    private static final Pattern PLAIN = Pattern.compile("[A-Z&.,'/\\- ]+");

    private final Pattern exact;
    private final Pattern loose;

    private WordMatch(Pattern exact, Pattern loose) {
        this.exact = exact;
        this.loose = loose;
    }

    public static WordMatch of(String pattern) {
        String upper = pattern.trim().toUpperCase(Locale.ROOT);
        String words = loosen(upper);
        boolean plain = PLAIN.matcher(upper).matches() && !words.isEmpty();
        return new WordMatch(Categorizer.wordPattern(upper), plain ? Categorizer.wordPattern(words) : null);
    }

    public boolean in(Text text) {
        return exact.matcher(text.exact()).find() || (loose != null && loose.matcher(text.loose()).find());
    }

    public boolean in(String text) {
        return in(Text.of(text));
    }

    /** A description prepared once for all the rules tried against it. */
    public record Text(String exact, String loose) {
        public static Text of(String text) {
            String upper = text.toUpperCase(Locale.ROOT);
            return new Text(upper, loosen(upper));
        }
    }

    /**
     * Words only, single-spaced: "ACME, INC. 10/15 #4471" becomes "ACME INC". Anything
     * with a digit in it is a date or a reference code and goes whole, so the letters
     * inside "JPM99ADP7K2" never pass for the word ADP.
     */
    static String loosen(String text) {
        StringBuilder words = new StringBuilder();
        for (String token : text.toUpperCase(Locale.ROOT).split("[^A-Z0-9&]+")) {
            if (!token.isEmpty() && token.chars().noneMatch(Character::isDigit)) {
                words.append(words.isEmpty() ? "" : " ").append(token);
            }
        }
        return words.toString();
    }
}
