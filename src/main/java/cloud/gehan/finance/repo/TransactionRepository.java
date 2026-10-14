package cloud.gehan.finance.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategorySource;
import cloud.gehan.finance.model.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByAccountAndExternalId(Account account, String externalId);

    List<Transaction> findByAccountAndExternalIdIn(Account account, List<String> externalIds);

    List<Transaction> findByAccountAndPendingTrue(Account account);

    @Query("select t from Transaction t where t.day between ?1 and ?2 and t.account.hidden = false")
    List<Transaction> findVisibleBetween(LocalDate from, LocalDate to);

    List<Transaction> findByDayGreaterThanEqual(LocalDate from);

    List<Transaction> findByCategorySourceNot(CategorySource source);

    List<Transaction> findByCategorySource(CategorySource source);

    List<Transaction> findByCategory(Category category);

    List<Transaction> findTop10ByAccountHiddenFalseOrderByDayDescIdDesc();

    /**
     * Where the bank history starts. Payments you typed in for months before it
     * (old rent, say) don't count: those months still aren't fully known.
     */
    @Query("select min(t.day) from Transaction t where t.externalId not like 'manual:bill:%'"
            + " and (t.account.connection is null or t.externalId not like 'manual:%')")
    Optional<LocalDate> earliestDay();

    /** Where the bank's own history of one account starts; what you typed in doesn't count. */
    @Query("select min(t.day) from Transaction t where t.account = ?1 and t.externalId not like 'manual:%'")
    Optional<LocalDate> earliestBankDay(Account account);

    @Query("select t from Transaction t where t.category = ?1 and t.account.hidden = false order by t.day desc, t.id desc")
    List<Transaction> findVisibleByCategory(Category category);

    @Modifying
    @Query("update Transaction t set t.reviewed = true where t.categorySource = ?1")
    int markReviewed(CategorySource source);

    @Modifying
    @Query("delete from Transaction t where t.account = ?1")
    void deleteByAccount(Account account);

    @Modifying
    @Query("update Transaction t set t.pairedTxnId = null where t.pairedTxnId in ?1")
    void unpair(List<Long> ids);
}
