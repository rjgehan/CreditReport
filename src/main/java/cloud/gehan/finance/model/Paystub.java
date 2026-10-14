package cloud.gehan.finance.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What one typical paycheck looks like before it reaches the bank. The bank only
 * ever sees net pay; this is what lets the Sankey start from gross and show where
 * taxes and the 401(k) went. Amounts are per paycheck.
 */
@Entity
@Table(name = "paystub")
public class Paystub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String employer;

    /** Words in the deposit's description that identify this employer, e.g. ACME PAYROLL. */
    private String matchPattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayFrequency frequency = PayFrequency.BIWEEKLY;

    private long grossCents;
    private long federalTaxCents;
    private long stateTaxCents;
    /** Social Security and Medicare together. */
    private long ficaCents;
    /** Pre-tax 401(k)/403(b). */
    private long retirementCents;
    /** Health, dental, vision, HSA, FSA. */
    private long benefitsCents;
    private long otherDeductionsCents;
    /** Employer 401(k) match: never touches the paycheck but is still yours. */
    private long employerMatchCents;

    private boolean active = true;

    protected Paystub() {
    }

    public Paystub(String employer) {
        this.employer = employer;
    }

    public long deductionsCents() {
        return federalTaxCents + stateTaxCents + ficaCents + retirementCents + benefitsCents + otherDeductionsCents;
    }

    public long netCents() {
        return grossCents - deductionsCents();
    }

    public Long getId() { return id; }
    public String getEmployer() { return employer; }
    public void setEmployer(String employer) { this.employer = employer; }
    public String getMatchPattern() { return matchPattern; }
    public void setMatchPattern(String matchPattern) { this.matchPattern = matchPattern; }
    public PayFrequency getFrequency() { return frequency; }
    public void setFrequency(PayFrequency frequency) { this.frequency = frequency; }
    public long getGrossCents() { return grossCents; }
    public void setGrossCents(long grossCents) { this.grossCents = grossCents; }
    public long getFederalTaxCents() { return federalTaxCents; }
    public void setFederalTaxCents(long federalTaxCents) { this.federalTaxCents = federalTaxCents; }
    public long getStateTaxCents() { return stateTaxCents; }
    public void setStateTaxCents(long stateTaxCents) { this.stateTaxCents = stateTaxCents; }
    public long getFicaCents() { return ficaCents; }
    public void setFicaCents(long ficaCents) { this.ficaCents = ficaCents; }
    public long getRetirementCents() { return retirementCents; }
    public void setRetirementCents(long retirementCents) { this.retirementCents = retirementCents; }
    public long getBenefitsCents() { return benefitsCents; }
    public void setBenefitsCents(long benefitsCents) { this.benefitsCents = benefitsCents; }
    public long getOtherDeductionsCents() { return otherDeductionsCents; }
    public void setOtherDeductionsCents(long otherDeductionsCents) { this.otherDeductionsCents = otherDeductionsCents; }
    public long getEmployerMatchCents() { return employerMatchCents; }
    public void setEmployerMatchCents(long employerMatchCents) { this.employerMatchCents = employerMatchCents; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
