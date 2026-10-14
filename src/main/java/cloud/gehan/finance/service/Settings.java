package cloud.gehan.finance.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.model.AppSetting;
import cloud.gehan.finance.repo.AppSettingRepository;

/** Typed access to the few preferences the app keeps. */
@Service
public class Settings {

    static final String SAVINGS_TARGET = "savings-target-cents";

    private final AppSettingRepository repo;
    private final TransactionTemplate tx;

    public Settings(AppSettingRepository repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    /** What you want left over at the end of each month, from take-home pay. Null until you set it. */
    public Long savingsTargetCents() {
        return repo.findById(SAVINGS_TARGET).map(s -> Long.valueOf(s.getValue())).orElse(null);
    }

    public void setSavingsTargetCents(Long cents) {
        tx.executeWithoutResult(s -> {
            if (cents == null || cents <= 0) {
                repo.deleteById(SAVINGS_TARGET);
            } else {
                AppSetting setting = repo.findById(SAVINGS_TARGET).orElseGet(() -> new AppSetting(SAVINGS_TARGET, "0"));
                setting.setValue(String.valueOf(cents));
                repo.save(setting);
            }
        });
    }
}
