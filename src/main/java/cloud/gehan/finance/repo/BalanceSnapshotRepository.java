package cloud.gehan.finance.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.BalanceSnapshot;

public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, Long> {

    Optional<BalanceSnapshot> findByAccountAndDay(Account account, LocalDate day);

    List<BalanceSnapshot> findByDayGreaterThanEqualOrderByDayAsc(LocalDate from);

    /** The last balance known before a date, to carry forward into a chart's first day. */
    Optional<BalanceSnapshot> findTopByAccountAndDayLessThanOrderByDayDesc(Account account, LocalDate day);

    Optional<BalanceSnapshot> findTopByAccountAndDayLessThanEqualOrderByDayDesc(Account account, LocalDate day);

    Optional<BalanceSnapshot> findTopByAccountOrderByDayAsc(Account account);

    long countByAccountAndDayGreaterThanEqual(Account account, LocalDate day);

    @Modifying
    @Query("delete from BalanceSnapshot s where s.account = ?1")
    void deleteByAccount(Account account);
}
