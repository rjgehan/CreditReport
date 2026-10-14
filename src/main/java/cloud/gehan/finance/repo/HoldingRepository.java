package cloud.gehan.finance.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Holding;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByAccount(Account account);

    Optional<Holding> findByAccountAndSymbol(Account account, String symbol);

    @Modifying
    @Query("delete from Holding h where h.account = ?1")
    void deleteByAccount(Account account);
}
