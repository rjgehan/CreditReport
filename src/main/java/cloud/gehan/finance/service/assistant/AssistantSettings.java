package cloud.gehan.finance.service.assistant;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.gehan.finance.model.AppSetting;
import cloud.gehan.finance.repo.AppSettingRepository;
import cloud.gehan.finance.security.SecretCipher;

/**
 * The assistant's Anthropic API key (encrypted like the bank tokens), the model it
 * uses, and what it has cost this month.
 */
@Component
public class AssistantSettings {

    static final String KEY = "assistant-api-key";
    static final String MODEL = "assistant-model";
    static final String USAGE = "assistant-usage-";

    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    /**
     * Per million tokens: input, output, cache read, cache write (5 minutes). Used only to
     * show roughly what the month has cost; Anthropic's console has the real bill.
     */
    record Price(double input, double output, double cacheRead, double cacheWrite) {
    }

    static final Map<String, Price> PRICES = Map.of(
            "claude-opus-5-5", new Price(4.00, 20.00, 0.20, 5.00),
            "claude-sonnet-5-5", new Price(2.00, 10.00, 0.20, 2.50),
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.01, 0.125),
            // Where a declined answer is retried.
            "claude-opus-5", new Price(5.00, 25.00, 0.50, 6.25),
            "claude-opus-4-8", new Price(5.00, 25.00, 0.50, 6.25));

    /** The ones offered, most capable first. */
    public static final List<String> MODELS = List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");

    private final AppSettingRepository repo;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;

    public AssistantSettings(AppSettingRepository repo, SecretCipher cipher, TransactionTemplate tx) {
        this.repo = repo;
        this.cipher = cipher;
        this.tx = tx;
    }

    /** The key pasted into the app, else ANTHROPIC_API_KEY from the environment, else null. */
    public String apiKey() {
        String stored = repo.findById(KEY).map(AppSetting::getValue).map(cipher::decrypt).orElse(null);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null || env.isBlank() ? null : env.trim();
    }

    /** Where the key came from: "app", "environment" or null. */
    public String keySource() {
        if (repo.existsById(KEY)) {
            return "app";
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null || env.isBlank() ? null : "environment";
    }

    public void setApiKey(String key) {
        put(KEY, key == null ? null : cipher.encrypt(key));
    }

    public String model() {
        String m = repo.findById(MODEL).map(AppSetting::getValue).orElse(null);
        return m != null && MODELS.contains(m) ? m : DEFAULT_MODEL;
    }

    public void setModel(String model) {
        put(MODEL, model);
    }

    /** @param microdollars millionths of a dollar */
    public record Usage(String month, int questions, long microdollars) {
    }

    public Usage usage(YearMonth month) {
        String v = repo.findById(USAGE + month).map(AppSetting::getValue).orElse("0,0");
        String[] parts = v.split(",");
        return new Usage(month.toString(), Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
    }

    public synchronized void addUsage(YearMonth month, int questions, long microdollars) {
        Usage u = usage(month);
        put(USAGE + month, (u.questions() + questions) + "," + (u.microdollars() + microdollars));
    }

    private void put(String key, String value) {
        tx.executeWithoutResult(s -> {
            if (value == null) {
                repo.deleteById(key);
                return;
            }
            AppSetting setting = repo.findById(key).orElseGet(() -> new AppSetting(key, value));
            setting.setValue(value);
            repo.save(setting);
        });
    }
}
