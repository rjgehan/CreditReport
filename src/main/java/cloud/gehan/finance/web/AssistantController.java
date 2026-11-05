package cloud.gehan.finance.web;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.service.assistant.AssistantService;
import cloud.gehan.finance.service.assistant.AssistantSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ask Claude about your money: setup (key and model) and the chat itself. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistant;
    private final AssistantSettings settings;
    private final AppProperties props;

    public AssistantController(AssistantService assistant, AssistantSettings settings, AppProperties props) {
        this.assistant = assistant;
        this.settings = settings;
        this.props = props;
    }

    public record ModelOption(String id, String name, String note) {
    }

    static final List<ModelOption> MODELS = List.of(
            new ModelOption("claude-opus-5-5", "Opus 5.5", "Most careful"),
            new ModelOption("claude-sonnet-5-5", "Sonnet 5.5", "About half the cost"),
            new ModelOption("claude-haiku-5-5", "Haiku 5.5", "Cheapest, quickest"));

    @GetMapping
    public Map<String, Object> status() {
        AssistantSettings.Usage usage = settings.usage(YearMonth.now(props.zoneId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", assistant.configured());
        out.put("keySource", settings.keySource());
        out.put("model", settings.model());
        out.put("models", MODELS);
        out.put("questions", usage.questions());
        out.put("costCents", Math.round(usage.microdollars() / 10_000.0));
        return out;
    }

    public record KeyBody(@NotBlank @Size(max = 300) String key) {
    }

    /** Checks the key with Anthropic before keeping it, encrypted. */
    @PutMapping("/key")
    public Map<String, Object> setKey(@RequestBody @Valid KeyBody body) {
        String key = body.key().trim();
        if (!key.startsWith("sk-ant-")) {
            throw new IllegalArgumentException("That doesn't look like an Anthropic API key (they start with sk-ant-).");
        }
        assistant.check(key, settings.model());
        settings.setApiKey(key);
        return status();
    }

    @DeleteMapping("/key")
    public Map<String, Object> removeKey() {
        settings.setApiKey(null);
        return status();
    }

    public record ModelBody(@NotBlank String model) {
    }

    @PutMapping("/model")
    public Map<String, Object> setModel(@RequestBody @Valid ModelBody body) {
        if (!AssistantSettings.MODELS.contains(body.model())) {
            throw new IllegalArgumentException("Unknown model");
        }
        settings.setModel(body.model());
        return status();
    }

    public record AskBody(String conversationId, @NotBlank @Size(max = 2000) String question) {
    }

    @PostMapping("/ask")
    public AssistantService.Reply ask(@RequestBody @Valid AskBody body) {
        return assistant.ask(body.conversationId(), body.question().trim());
    }

    @GetMapping("/chats/{id}")
    public Map<String, Object> chat(@PathVariable String id) {
        List<AssistantService.Turn> turns = assistant.transcript(id);
        if (turns == null) {
            throw new NoSuchElementException();
        }
        return Map.of("conversationId", id, "turns", turns);
    }

    @DeleteMapping("/chats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String id) {
        assistant.forget(id);
    }
}
