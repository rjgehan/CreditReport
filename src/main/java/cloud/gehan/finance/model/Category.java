package cloud.gehan.finance.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private CategoryKind kind;

    @Column(nullable = false)
    private String color;

    private String icon;

    private int sortOrder;

    /**
     * Income kind only: money earned from work (a paycheck, a side hustle). Interest,
     * dividends and stray deposits arrive as income-kind money too, but don't count
     * as income anywhere in the app.
     */
    private Boolean earnedIncome;

    /** Expense kind only: the same every month (rent, loan payments), so not worth watching month to month. */
    private Boolean fixedCost;

    protected Category() {
    }

    public Category(String name, CategoryKind kind, String color, String icon, int sortOrder) {
        this.name = name;
        this.kind = kind;
        this.color = color;
        this.icon = icon;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CategoryKind getKind() { return kind; }
    public void setKind(CategoryKind kind) { this.kind = kind; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEarnedIncome() { return Boolean.TRUE.equals(earnedIncome); }
    public Boolean getEarnedIncome() { return earnedIncome; }
    public void setEarnedIncome(Boolean earnedIncome) { this.earnedIncome = earnedIncome; }
    public boolean isFixedCost() { return Boolean.TRUE.equals(fixedCost); }
    public Boolean getFixedCost() { return fixedCost; }
    public void setFixedCost(Boolean fixedCost) { this.fixedCost = fixedCost; }
}
