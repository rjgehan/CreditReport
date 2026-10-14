package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.RecurringBuy;

public interface RecurringBuyRepository extends JpaRepository<RecurringBuy, Long> {

    List<RecurringBuy> findByActiveTrue();

    List<RecurringBuy> findByAccount(Account account);

    @Modifying
    @Query("delete from RecurringBuy r where r.account = ?1")
    void deleteByAccount(Account account);
}
