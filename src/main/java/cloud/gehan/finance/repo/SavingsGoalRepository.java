package cloud.gehan.finance.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Account;
import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.SavingsGoal;

public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {

    List<SavingsGoal> findByAccount(Account account);

    List<SavingsGoal> findByCategory(Category category);
}
