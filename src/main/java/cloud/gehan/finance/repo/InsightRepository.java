package cloud.gehan.finance.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Insight;

public interface InsightRepository extends JpaRepository<Insight, Long> {

    Optional<Insight> findFirstByKindOrderByCreatedAtDesc(Insight.Kind kind);

    Optional<Insight> findByKindAndPeriod(Insight.Kind kind, String period);
}
