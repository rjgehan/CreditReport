package cloud.gehan.finance.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cloud.gehan.finance.service.StatementImporter.Parsed;
import cloud.gehan.finance.service.StatementImporter.Row;

/**
 * Reads OFX, QFX and QBO statements. Version 1 files are SGML, where leaf tags
 * often have no closing tag (&lt;TRNAMT&gt;-12.34 then a newline); version 2 is
 * XML. Reading each value up to the next '&lt;' or line end handles both.
 *
 * <p>OFX signs amounts from the account holder's side: a card purchase or a
 * debit is negative, a deposit or a card payment is positive. That is already
 * this app's convention.
 */
final class Ofx {

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|</BANKTRANLIST>)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LEDGER = Pattern.compile("<LEDGERBAL>(.*?)(</LEDGERBAL>|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private Ofx() {
    }

    static boolean looksLikeOfx(String text) {
        String head = text.substring(0, Math.min(text.length(), 2000)).toUpperCase(Locale.ROOT);
        return head.contains("OFXHEADER") || head.contains("<OFX>") || head.contains("<?OFX");
    }

    static Parsed parse(String text, List<String> problems) {
        List<Row> rows = new ArrayList<>();
        Matcher m = TRANSACTION.matcher(text);
        int index = 0;
        while (m.find()) {
            index++;
            String block = m.group(1);
            try {
                String amount = tag(block, "TRNAMT");
                String posted = tag(block, "DTPOSTED");
                if (amount == null || posted == null) {
                    throw new IllegalArgumentException("missing TRNAMT or DTPOSTED");
                }
                String name = tag(block, "NAME");
                String memo = tag(block, "MEMO");
                String payee = tag(block, "PAYEE");
                String description = name != null ? name : payee != null ? payee : memo != null ? memo : "Imported transaction";
                // Many banks truncate NAME to 32 characters and put the rest in MEMO.
                if (name != null && memo != null && !memo.equalsIgnoreCase(name) && name.length() >= 30) {
                    description = name + " " + memo;
                }
                rows.add(new Row(day(posted), StatementImporter.money(amount), unescape(description), tag(block, "FITID")));
            } catch (RuntimeException e) {
                if (problems.size() < 10) {
                    problems.add("Transaction " + index + ": " + e.getMessage());
                }
            }
        }

        Long balance = null;
        LocalDate asOf = null;
        Matcher ledger = LEDGER.matcher(text);
        if (ledger.find()) {
            String amount = tag(ledger.group(1), "BALAMT");
            String date = tag(ledger.group(1), "DTASOF");
            if (amount != null) {
                balance = StatementImporter.money(amount);
                asOf = date == null ? null : day(date);
            }
        }
        if (rows.isEmpty() && balance == null) {
            throw new IllegalArgumentException("No transactions or balance found in this OFX file.");
        }
        return new Parsed(rows, balance, asOf, "OFX");
    }

    /** OFX dates are yyyyMMdd, optionally followed by time, fraction and [zone]. */
    static LocalDate day(String value) {
        String digits = value.trim();
        if (digits.length() < 8) {
            throw new IllegalArgumentException("bad date '" + value + "'");
        }
        return LocalDate.parse(digits.substring(0, 8), DAY);
    }

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).trim();
        return value.isEmpty() ? null : value;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
    }
}
