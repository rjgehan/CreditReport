package cloud.gehan.finance.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.Category;
import cloud.gehan.finance.model.CategoryRole;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByNameIgnoreCase(String name);

    List<Category> findAllByOrderBySortOrderAscNameAsc();

    /** The category doing this job, if any. */
    default Optional<Category> findByRole(CategoryRole role) {
        return findAll().stream().filter(c -> c.hasRole(role)).findFirst();
    }
}
