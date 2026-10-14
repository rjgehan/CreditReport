package cloud.gehan.finance.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Connection;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByConnectionAndExternalId(Connection connection, String externalId);

    List<Account> findByConnection(Connection connection);

    List<Account> findByHiddenFalse();
}
