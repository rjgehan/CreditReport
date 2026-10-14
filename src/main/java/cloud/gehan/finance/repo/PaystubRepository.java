package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Paystub;

public interface PaystubRepository extends JpaRepository<Paystub, Long> {

    List<Paystub> findByActiveTrue();
}
