package cloud.gehan.finance.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Question;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findFirstByOrderByCreatedAtDesc();
}
