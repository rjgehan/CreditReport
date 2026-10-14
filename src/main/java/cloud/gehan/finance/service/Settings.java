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

    static final String RECURRING_HIDDEN = "recurring-hidden";

    /** Regular charges you said aren't regular, by their key on Monthly expenses. */
    public java.util.Set<String> hiddenRecurring() {
        return repo.findById(RECURRING_HIDDEN)
                .map(s -> new java.util.LinkedHashSet<>(java.util.Arrays.asList(s.getValue().split("\n"))))
                .map(set -> { set.remove(""); return (java.util.Set<String>) set; })
                .orElseGet(java.util.LinkedHashSet::new);
    }

    public void setHiddenRecurring(java.util.Set<String> keys) {
        tx.executeWithoutResult(s -> {
            if (keys.isEmpty()) {
                repo.deleteById(RECURRING_HIDDEN);
                return;
            }
            AppSetting setting = repo.findById(RECURRING_HIDDEN).orElseGet(() -> new AppSetting(RECURRING_HIDDEN, ""));
            setting.setValue(String.join("\n", keys));
            repo.save(setting);
        });
    }

    static final String SHORTCUT_TOKEN = "shortcut-token-sha256";

    /** SHA-256 of the iPhone shortcut's token, or null if none was ever made. */
    public String shortcutTokenHash() {
        return repo.findById(SHORTCUT_TOKEN).map(AppSetting::getValue).orElse(null);
    }

    public void setShortcutTokenHash(String hash) {
        tx.executeWithoutResult(s -> {
            AppSetting setting = repo.findById(SHORTCUT_TOKEN).orElseGet(() -> new AppSetting(SHORTCUT_TOKEN, hash));
            setting.setValue(hash);
            repo.save(setting);
        });
    }
}
