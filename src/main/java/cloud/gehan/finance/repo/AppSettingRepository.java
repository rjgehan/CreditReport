package cloud.gehan.finance.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.gehan.finance.model.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
