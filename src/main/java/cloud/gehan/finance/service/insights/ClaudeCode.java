package cloud.gehan.finance.service.insights;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.model.AppSetting;
import cloud.gehan.finance.repo.AppSettingRepository;
import cloud.gehan.finance.security.SecretCipher;

/**
 * Claude, through Claude Code in print mode on your own Claude subscription, the
 * way the stocks app's trader runs it. No API key: the sign-in is the token
 * `claude setup-token` makes, kept encrypted like the bank token.
 *
 * <p>Each run is one question with the data in it and a JSON Schema for the
 * answer. Claude gets no tools at all (no files, no shell, no MCP servers), so all
 * it can do is read what it's given and answer.
 */
@Component
public class ClaudeCode {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCode.class);
    static final String TOKEN = "claude-code-token";
    static final Duration TIMEOUT = Duration.ofMinutes(4);

    /** Starts the process; replaced in tests. */
    public interface Cli {
        /** Runs the command with the prompt on stdin and returns stdout, or throws Problem. */
        String run(List<String> command, String input, Map<String, String> env, Path dir, Duration timeout);
    }

    /** Something to tell you, not a bug. */
    public static class Problem extends RuntimeException {
        public Problem(String message) {
            super(message);
        }
    }

    private final Cli cli;
    private final AppSettingRepository settings;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Path home;
    private final String bin;
    private final String model;
    /** One run at a time: the container has room for one Claude Code process next to the app. */
    private final ReentrantLock running = new ReentrantLock();

    public ClaudeCode(Cli cli, AppSettingRepository settings, SecretCipher cipher, TransactionTemplate tx,
            ObjectMapper json, AppProperties props, @Value("${app.claude-bin:claude}") String bin,
            @Value("${app.claude-model:}") String model) {
        this.cli = cli;
        this.settings = settings;
        this.cipher = cipher;
        this.tx = tx;
        this.json = json;
        this.home = Path.of(props.dataDir(), "claude");
        this.bin = bin;
        this.model = model;
    }

    // --- The sign-in token --------------------------------------------------------------

    public boolean configured() {
        return settings.existsById(TOKEN);
    }

    public void setToken(String token) {
        tx.executeWithoutResult(s -> {
            if (token == null) {
                settings.findById(TOKEN).ifPresent(settings::delete);
                return;
            }
            AppSetting a = settings.findById(TOKEN).orElseGet(() -> new AppSetting(TOKEN, ""));
            a.setValue(cipher.encrypt(token));
            settings.save(a);
        });
    }

    private String token() {
        return settings.findById(TOKEN).map(AppSetting::getValue).map(cipher::decrypt).orElse(null);
    }

    public boolean busy() {
        return running.isLocked();
    }

    // --- Asking -------------------------------------------------------------------------

    /** Asks with a token that isn't saved yet, to check it works. */
    public JsonNode ask(String system, String prompt, Map<String, Object> schema, String withToken) {
        return run(system, prompt, schema, withToken);
    }

    /** Asks and returns the answer, shaped by the schema. */
    public JsonNode ask(String system, String prompt, Map<String, Object> schema) {
        String token = token();
        if (token == null) {
            throw new Problem("Claude isn't set up yet: Accounts, More, Claude.");
        }
        return run(system, prompt, schema, token);
    }

    private JsonNode run(String system, String prompt, Map<String, Object> schema, String token) {
        if (!running.tryLock()) {
            throw new Problem("Claude is already working on something. Try again in a minute.");
        }
        try {
            Path work = home.resolve("work");
            Files.createDirectories(work);
            List<String> cmd = new ArrayList<>(List.of(bin, "-p",
                    "--output-format", "json",
                    "--json-schema", json.writeValueAsString(schema),
                    "--system-prompt", system,
                    // Nothing but the answer: no tools, no MCP servers, no settings or skills.
                    "--tools", "",
                    "--strict-mcp-config",
                    "--setting-sources", "",
                    "--disable-slash-commands",
                    "--no-session-persistence"));
            if (model != null && !model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            String out = cli.run(cmd, prompt, env(token), work, TIMEOUT);
            return result(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            running.unlock();
        }
    }

    /** Only what Claude Code needs; none of the app's own secrets. */
    private Map<String, String> env(String token) {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDE_CODE_OAUTH_TOKEN", token);
        env.put("HOME", home.toAbsolutePath().toString());
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        for (String keep : List.of("PATH", "TZ", "LANG", "SYSTEMROOT", "TEMP", "TMP")) {
            String v = System.getenv(keep);
            if (v != null) {
                env.put(keep, v);
            }
        }
        return env;
    }

    JsonNode result(String out) {
        JsonNode r;
        try {
            r = json.readTree(out.substring(Math.max(0, out.indexOf('{'))));
        } catch (IOException | RuntimeException e) {
            log.warn("Claude Code printed something that isn't its JSON result: {}", clip(out));
            throw new Problem("Claude didn't answer properly. Try again.");
        }
        if (r.path("is_error").asBoolean(false) || !"success".equals(r.path("subtype").asText("success"))) {
            String why = r.path("result").asText(r.path("subtype").asText("an error"));
            log.warn("Claude Code reported an error: {}", clip(why));
            throw new Problem(explain(why));
        }
        JsonNode answer = r.get("structured_output");
        if (answer == null || answer.isNull()) {
            try {
                answer = json.readTree(r.path("result").asText(""));
            } catch (IOException e) {
                answer = null;
            }
        }
        if (answer == null || !answer.isObject()) {
            throw new Problem("Claude didn't answer properly. Try again.");
        }
        return answer;
    }

    static String explain(String why) {
        String w = why.toLowerCase(Locale.ROOT);
        if (w.contains("401") || w.contains("oauth") || w.contains("authenticat") || w.contains("login")
                || w.contains("invalid api key")) {
            return "Claude's sign-in token didn't work. Make a new one with claude setup-token (Accounts, More, Claude).";
        }
        if (w.contains("limit") || w.contains("429") || w.contains("usage")) {
            return "Your Claude plan's usage limit is reached for now. It'll work again once it resets.";
        }
        return "Claude couldn't answer right now. Try again in a bit.";
    }

    static String clip(String s) {
        return s == null ? "" : s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** Runs the real `claude` binary. */
    @Component
    static class ProcessCli implements Cli {
        @Override
        public String run(List<String> command, String input, Map<String, String> env, Path dir, Duration timeout) {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(false);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new Problem("Claude Code isn't installed on the server (" + e.getMessage() + ").");
            }
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (OutputStream in = p.getOutputStream()) {
                in.write(input.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // It exited before reading; its output says why.
            }
            try {
                if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    throw new Problem("Claude took too long. Try again.");
                }
                String stdout = out.get(10, TimeUnit.SECONDS);
                if (p.exitValue() != 0 && stdout.isBlank()) {
                    String stderr = err.get(10, TimeUnit.SECONDS);
                    log.warn("Claude Code exited {}: {}", p.exitValue(), clip(stderr));
                    throw new Problem(explain(stderr));
                }
                return stdout;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new Problem("Claude was interrupted.");
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new Problem("Claude's answer couldn't be read.");
            }
        }

        private static String read(InputStream s) {
            try (s) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    }
}
