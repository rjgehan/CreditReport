package cloud.gehan.finance.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.SpendingTarget;

public interface SpendingTargetRepository extends JpaRepository<SpendingTarget, Long> {

    Optional<SpendingTarget> findByCategory(Category category);

    @Modifying
    @Query("delete from SpendingTarget t where t.category = ?1")
    void deleteByCategory(Category category);
}
