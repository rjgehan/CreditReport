package cloud.gehan.finance.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Transaction;
import cloud.gehan.finance.repo.AccountRepository;
import cloud.gehan.finance.repo.TransactionRepository;

/**
 * Imports a downloaded statement into one account: the free way in for anything
 * Teller doesn't reach, and for history from before a connection existed.
 *
 * <p>OFX/QFX/QBO (the "Quicken" or "Money" download nearly every bank offers) is
 * preferred: it carries the bank's own transaction ids and the closing balance.
 * CSV columns are found by header name (Date, Description, Amount, or separate
 * Debit and Credit). Importing the same file twice adds nothing either way.
 */
@Service
public class StatementImporter {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("M-d-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));

    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final Categorizer categorizer;
    private final TransferMatcher matcher;
    private final Balances balances;
    private final TransactionTemplate tx;
    private final ZoneId zone;

    public StatementImporter(AccountRepository accounts, TransactionRepository txns, Categorizer categorizer,
            TransferMatcher matcher, Balances balances, TransactionTemplate tx, AppProperties props) {
        this.accounts = accounts;
        this.txns = txns;
        this.categorizer = categorizer;
        this.matcher = matcher;
        this.balances = balances;
        this.tx = tx;
        this.zone = props.zoneId();
    }

    /** @param balanceCents the statement's closing balance, when the file carried one (OFX does) */
    public record Result(int imported, int duplicates, List<String> problems, String format, Long balanceCents) {
    }

    /** @param id the bank's own transaction id (OFX FITID), when the file has one */
    record Row(LocalDate day, long amountCents, String description, String id) {
    }

    record Parsed(List<Row> rows, Long balanceCents, LocalDate balanceDay, String format) {
    }

    /**
     * @param flipSign for CSV exports that write purchases as positive numbers
     *        (several card issuers do). OFX has a defined sign and ignores it.
     */
    public Result importFile(Long accountId, InputStream in, boolean flipSign) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<String> problems = new ArrayList<>();
        Parsed parsed = Ofx.looksLikeOfx(text) ? Ofx.parse(text, problems) : new Parsed(parse(text, flipSign, problems), null, null, "CSV");
        LocalDate today = LocalDate.now(zone);
        return tx.execute(s -> {
            Account account = accounts.findById(accountId).orElseThrow();
            Map<String, Integer> seen = new HashMap<>();
            List<Transaction> created = new ArrayList<>();
            int duplicates = 0;
            LocalDate earliest = LocalDate.MAX;
            for (Row r : parsed.rows()) {
                String id;
                if (r.id() != null && !r.id().isBlank()) {
                    id = "ofx:" + r.id();
                } else {
                    String fingerprint = r.day() + "|" + r.amountCents() + "|" + r.description();
                    int n = seen.merge(fingerprint, 1, Integer::sum);
                    id = "csv:" + sha256(fingerprint + "|" + n);
                }
                if (txns.findByAccountAndExternalId(account, id).isPresent()) {
                    duplicates++;
                    continue;
                }
                created.add(txns.save(new Transaction(account, id, r.day(), r.amountCents(), r.description())));
                if (r.day().isBefore(earliest)) {
                    earliest = r.day();
                }
            }
            categorizer.categorize(created);
            if (!created.isEmpty()) {
                matcher.match(txns.findByDayGreaterThanEqual(earliest.minusDays(TransferMatcher.MAX_DAYS_APART + 1)),
                        categorizer.context());
            }
            Long balance = parsed.balanceCents();
            if (balance != null && account.isManual()) {
                // A card statement's balance is what you owe; stored here as negative.
                if (account.getType().liability() && balance > 0) {
                    balance = -balance;
                }
                LocalDate day = parsed.balanceDay() == null || parsed.balanceDay().isAfter(today) ? today : parsed.balanceDay();
                boolean latest = account.getBalanceUpdatedAt() == null
                        || !day.isBefore(account.getBalanceUpdatedAt().atZone(zone).toLocalDate());
                balances.record(account, day, balance, latest);
            }
            return new Result(created.size(), duplicates, problems, parsed.format(), balance);
        });
    }

    static List<Row> parse(String text, boolean flipSign, List<String> problems) {
        List<List<String>> table = split(text);
        if (table.isEmpty()) {
            throw new IllegalArgumentException("The file is empty.");
        }
        List<String> header = table.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        int date = find(header, "posting date", "post date", "posted date", "date", "transaction date", "trans. date");
        if (date < 0) {
            date = findContaining(header, "date");
        }
        int description = find(header, "description", "payee", "name", "merchant", "memo", "details", "transaction");
        int amount = find(header, "amount", "transaction amount", "amount (usd)");
        int debit = find(header, "debit", "withdrawal", "withdrawals", "debit amount");
        int credit = find(header, "credit", "deposit", "deposits", "credit amount");
        if (date < 0 || description < 0 || (amount < 0 && debit < 0 && credit < 0)) {
            throw new IllegalArgumentException(
                    "Could not find Date, Description and Amount (or Debit/Credit) columns in the header: " + header);
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            try {
                LocalDate day = date(cell(cells, date));
                long cents;
                if (amount >= 0) {
                    cents = money(cell(cells, amount));
                } else {
                    long out = debit >= 0 ? Math.abs(money(cell(cells, debit))) : 0;
                    long inn = credit >= 0 ? Math.abs(money(cell(cells, credit))) : 0;
                    cents = inn - out;
                }
                if (flipSign) {
                    cents = -cents;
                }
                String desc = cell(cells, description).trim();
                rows.add(new Row(day, cents, desc.isEmpty() ? "Imported transaction" : desc, null));
            } catch (RuntimeException e) {
                if (problems.size() < 10) {
                    problems.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        return rows;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : "";
    }

    private static int find(List<String> header, String... names) {
        for (String name : names) {
            int i = header.indexOf(name);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static int findContaining(List<String> header, String part) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    static LocalDate date(String raw) {
        String value = raw.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("unrecognised date '" + value + "'");
    }

    /** "$1,234.56", "(12.00)" and "-12" all work. Blank is zero. */
    static long money(String raw) {
        String v = raw.trim().replace("$", "").replace(",", "").replace(" ", "");
        if (v.isEmpty()) {
            return 0;
        }
        boolean negative = v.startsWith("(") && v.endsWith(")");
        if (negative) {
            v = v.substring(1, v.length() - 1);
        }
        long cents = new BigDecimal(v).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return negative ? -cents : cents;
    }

    /** RFC 4180-ish: quoted fields, doubled quotes, commas and newlines inside quotes. */
    static List<List<String>> split(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String sha256(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
