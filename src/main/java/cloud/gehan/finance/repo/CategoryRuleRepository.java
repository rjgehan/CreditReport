package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryRule;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {

    List<CategoryRule> findAllByOrderByPriorityDescIdAsc();

    @Query("select coalesce(max(r.priority), 0) from CategoryRule r")
    int maxPriority();

    @Modifying
    @Query("delete from CategoryRule r where r.category = ?1")
    void deleteByCategory(Category category);
}
