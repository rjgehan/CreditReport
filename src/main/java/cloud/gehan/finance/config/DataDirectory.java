package cloud.gehan.finance.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * SQLite creates the database file but not the directory it sits in, and the
 * datasource opens before any bean could make it. This runs once the
 * configuration is resolved, so app.data-dir works from any source.
 */
public class DataDirectory implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String dir = env.getProperty("app.data-dir");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the data directory " + dir, e);
        }
    }
}
