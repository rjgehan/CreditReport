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
    }
}
