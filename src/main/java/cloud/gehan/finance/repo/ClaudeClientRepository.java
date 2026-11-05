package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.ClaudeClient;

public interface ClaudeClientRepository extends JpaRepository<ClaudeClient, String> {

    List<ClaudeClient> findAllByOrderByCreatedAtAsc();
}
