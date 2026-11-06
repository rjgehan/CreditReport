package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.InsightItem;

public interface InsightItemRepository extends JpaRepository<InsightItem, Long> {

    List<InsightItem> findByStateOrderByCreatedAtDesc(InsightItem.State state);

    List<InsightItem> findByKind(InsightItem.Kind kind);
}
