package cloud.gehan.finance.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.ClaudeGrant;

public interface ClaudeGrantRepository extends JpaRepository<ClaudeGrant, Long> {

    Optional<ClaudeGrant> findByAccessHash(String hash);

    Optional<ClaudeGrant> findByRefreshHash(String hash);

    List<ClaudeGrant> findByClientId(String clientId);

    boolean existsByClientId(String clientId);

    List<ClaudeGrant> findAllByOrderByCreatedAtDesc();
}
