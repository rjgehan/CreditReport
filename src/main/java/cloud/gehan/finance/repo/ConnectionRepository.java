package cloud.gehan.finance.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Connection;
import cloud.gehan.finance.model.ProviderType;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    List<Connection> findAllByOrderByCreatedAtAsc();

    List<Connection> findByProviderNot(ProviderType provider);

    Optional<Connection> findByProviderAndExternalId(ProviderType provider, String externalId);
}
