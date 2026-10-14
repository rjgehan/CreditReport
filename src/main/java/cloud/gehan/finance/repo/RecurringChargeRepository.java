package cloud.gehan.finance.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.RecurringCharge;

public interface RecurringChargeRepository extends JpaRepository<RecurringCharge, Long> {
}
