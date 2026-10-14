package cloud.gehan.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.gehan.finance.model.AccountType;
import cloud.gehan.finance.provider.AccountTypeGuesser;

class ParsingTest {

    @Test
    void rulesMatchWholeWords() {
        assertThat(Categorizer.wordPattern("RENT").matcher("AVALON APARTMENTS RENT").find()).isTrue();
        assertThat(Categorizer.wordPattern("RENT").matcher("CURRENT BALANCE").find()).isFalse();
        assertThat(Categorizer.wordPattern("SQ *").matcher("SQ *JOES COFFEE").find()).isTrue();
        assertThat(Categorizer.wordPattern("AMAZON").matcher("AMAZON.COM*2K4L1").find()).isTrue();
    }

    @Test
    void csvWithDebitAndCreditColumns() {
        String csv = "Date,Description,Debit,Credit\n"
                + "03/14/2026,\"STARBUCKS, NYC\",4.50,\n"
                + "03/15/2026,PAYROLL,,\"2,000.00\"\n";
        List<String> problems = new ArrayList<>();
        var rows = StatementImporter.parse(csv, false, problems);
        assertThat(problems).isEmpty();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).amountCents()).isEqualTo(-450);
        assertThat(rows.get(0).description()).isEqualTo("STARBUCKS, NYC");
        assertThat(rows.get(1).amountCents()).isEqualTo(200000);
    }

    @Test
    void ofxSgmlWithoutClosingTags() {
        String ofx = "OFXHEADER:100\nDATA:OFXSGML\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>\n"
                + "<STMTTRN>\n<TRNTYPE>DEBIT\n<DTPOSTED>20260301120000[-5:EST]\n<TRNAMT>-42.10\n<FITID>abc123\n<NAME>WHOLE FOODS\n</STMTTRN>\n"
                + "<STMTTRN>\n<TRNTYPE>CREDIT\n<DTPOSTED>20260302\n<TRNAMT>1500.00\n<FITID>abc124\n<NAME>ACME PAYROLL\n</STMTTRN>\n"
                + "</BANKTRANLIST><LEDGERBAL><BALAMT>2345.67<DTASOF>20260303</LEDGERBAL></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
        assertThat(Ofx.looksLikeOfx(ofx)).isTrue();
        var parsed = Ofx.parse(ofx, new ArrayList<>());
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).amountCents()).isEqualTo(-4210);
        assertThat(parsed.rows().get(0).id()).isEqualTo("abc123");
        assertThat(parsed.rows().get(0).day()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(parsed.balanceCents()).isEqualTo(234567);
    }

    @Test
    void accountTypeFromName() {
        assertThat(AccountTypeGuesser.guess("Navy Federal Credit Union Checking", null, 100)).isEqualTo(AccountType.CHECKING);
        assertThat(AccountTypeGuesser.guess("Freedom Unlimited", "Chase", -5000)).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(AccountTypeGuesser.guess("401(k) Plan", "PCS Retirement", 100)).isEqualTo(AccountType.RETIREMENT);
        assertThat(AccountTypeGuesser.guess("SoFi Savings", "SoFi", 100)).isEqualTo(AccountType.SAVINGS);
    }

    @Test
    void payerKeyIgnoresReferenceNumbers() {
        assertThat(PaycheckService.payerKey("ACME CORP PAYROLL PPD ID: 9876543210")).isEqualTo("ACME CORP PAYROLL");
        assertThat(PaycheckService.payerKey("ORIG CO NAME:ACME, INC. CO ENTRY DESCR:PAYROLL SEC:PPD")).isEqualTo("ACME INC");
    }

    @Test
    void rulesLookPastPunctuationAndNumbers() {
        assertThat(WordMatch.of("ACME INC").in("ACME, INC. 1015 EDI PYMNTS")).isTrue();
        assertThat(WordMatch.of("ACME INC PAYROLL").in("ACME INC 10/15 PAYROLL")).isTrue();
        assertThat(WordMatch.of("UBER EATS").in("UBER*EATS 8005928996")).isTrue();
        assertThat(WordMatch.of("ACME INC").in("ACMEINC PAYROLL")).isFalse();
        assertThat(WordMatch.of("RENT").in("CURRENT BALANCE")).isFalse();
        // Letters inside a reference code aren't words.
        assertThat(WordMatch.of("ADP").in("ZELLE PAYMENT FROM JANE JPM99ADP7K2")).isFalse();
        assertThat(WordMatch.of("BP").in("MOBILE DEPOSIT REF 4BP77")).isFalse();
        // Digits and symbols are part of the name and match as typed.
        assertThat(WordMatch.of("7-ELEVEN").in("ELEVEN MADISON PARK")).isFalse();
        assertThat(WordMatch.of("SQ *").in("SQ JOES")).isFalse();
    }

    @Test
    void whatYouDidToAPendingChargeFollowsItWhenItPosts() {
        cloud.gehan.finance.model.Account card = new cloud.gehan.finance.model.Account(null, "c1", "Card", AccountType.CREDIT_CARD);
        cloud.gehan.finance.model.Category dining = new cloud.gehan.finance.model.Category("Dining",
                cloud.gehan.finance.model.CategoryKind.EXPENSE, "#000", null, 0);
        LocalDate day = LocalDate.of(2026, 10, 10);
        cloud.gehan.finance.model.Transaction pending = new cloud.gehan.finance.model.Transaction(card, "p1", day, -4000, "JOES");
        pending.setPending(true);
        pending.setReviewed(true);
        pending.setCategory(dining);
        pending.setCategorySource(cloud.gehan.finance.model.CategorySource.MANUAL);
        pending.setNotes("with Sam");
        // The posted one has the tip on it; an unrelated bigger charge doesn't match.
        cloud.gehan.finance.model.Transaction posted = new cloud.gehan.finance.model.Transaction(card, "x1", day.plusDays(2), -4800, "JOES");
        cloud.gehan.finance.model.Transaction other = new cloud.gehan.finance.model.Transaction(card, "x2", day.plusDays(1), -20000, "STORE");

        SyncService.carryOver(List.of(pending), List.of(other, posted));

        assertThat(posted.isReviewed()).isTrue();
        assertThat(posted.getCategory()).isSameAs(dining);
        assertThat(posted.getCategorySource()).isEqualTo(cloud.gehan.finance.model.CategorySource.MANUAL);
        assertThat(posted.getNotes()).isEqualTo("with Sam");
        assertThat(other.isReviewed()).isFalse();
    }

    @Test
    void payRulesAreMoreThanOneStoreWord() {
        List<String> bofa = List.of("TARGET DES:PAYROLL ID:XXXXX12 INDN:DOE,JOHN CO ID:XXXXX34 PPD",
                "TARGET DES:PAYROLL ID:XXXXX56 INDN:DOE,JOHN CO ID:XXXXX34 PPD");
        assertThat(PaycheckService.payerKey(bofa.get(0))).isEqualTo("TARGET");
        assertThat(PaycheckService.ruleWords(bofa, "TARGET")).isEqualTo("TARGET DES PAYROLL");

        List<String> chase = List.of("ORIG CO NAME:3M COMPANY ORIG ID:1234567 DESC DATE:101524 CO ENTRY DESCR:PAYROLL SEC:PPD",
                "ORIG CO NAME:3M COMPANY ORIG ID:1234567 DESC DATE:103124 CO ENTRY DESCR:PAYROLL SEC:PPD");
        assertThat(PaycheckService.payerKey(chase.get(0))).isEqualTo("COMPANY");
        assertThat(PaycheckService.ruleWords(chase, "COMPANY")).isEqualTo("ORIG CO NAME COMPANY");
        assertThat(WordMatch.of("ORIG CO NAME COMPANY").in("ORIG CO NAME:THE GAS COMPANY ORIG ID:99 CO ENTRY DESCR:REFUND")).isFalse();

        List<String> acme = List.of("ACME, INC. 0915 EDI PYMNTS", "ACME, INC. 0930 EDI PYMNTS");
        assertThat(PaycheckService.ruleWords(acme, "ACME INC EDI")).isEqualTo("ACME INC EDI");
    }

    @Test
    void payerKeyIsFoundInTheDescriptionItCameFrom() {
        for (String d : List.of("ACME CORP PAYROLL PPD ID: 9876543210", "ACME, INC. 10/15 EDI PYMNTS",
                "ACME-INC. PAYROLL 0423", "Acme Inc.; Deposit #4471", "1015 ACME INC",
                "ORIG CO NAME:ACME, INC. CO ENTRY DESCR:PAYROLL SEC:PPD TRACE#:091000019")) {
            String key = PaycheckService.payerKey(d);
            assertThat(key).as(d).isNotBlank();
            assertThat(WordMatch.of(key).in(d)).as(d).isTrue();
        }
    }
}
