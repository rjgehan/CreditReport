package cloud.gehan.finance.service.assistant;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import cloud.gehan.finance.config.AppProperties;

/**
 * Questions about your money, answered by Claude reading the app's data through
 * {@link AssistantTools}. Conversations live in memory: one person, a handful of
 * chats, and nothing lost that matters if the app restarts.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** Rounds of looking things up before it has to answer with what it has. */
    private static final int MAX_STEPS = 10;
    private static final int MAX_CONVERSATIONS = 20;
    private static final Duration FORGET_AFTER = Duration.ofHours(12);

    static final String SYSTEM = """
            You are the assistant inside Finance, a personal finance app one person runs for themselves on their \
            home server. They ask about their own money; you answer with the tools, which read the app's data: \
            transactions, monthly totals, this month's outlook, spending targets and goals, recurring charges, rent \
            and loans, and paychecks.

            What you can't see, by design: account balances, net worth, and what investments are worth. If asked, \
            say so and point them to the Accounts tab. Don't try to work a balance out from transactions.

            You can only read. You can't recategorize, add rules, mark things or change settings; when they want \
            something changed, say where in the app to do it (Activity for transactions and categories, Home for \
            targets and goals, Accounts > More for monthly expenses, rent and loans, categories and paychecks).

            How the data works:
            - Amounts are US dollars. In transactions, negative is money out and positive is money in.
            - Pay arrives after taxes and 401(k) contributions, so income here is take-home.
            - Credit card payments and moves between their own accounts are transfers: neither income nor spending.
            - A one-off is a purchase they marked to keep out of their budget and averages (furniture after a move, \
            say). Mention one-offs when they matter to the answer, but don't count them as usual spending.
            - Transactions are filed into categories by rules, so a few may be in the wrong one. If a total looks off \
            because of that, say which transactions and suggest fixing them in Activity.
            - Bank history only goes back so far (history_starts in results). Say so when a question reaches past it.

            Every number you give must come from a tool result or be arithmetic on them; never estimate or invent \
            one. When the tools can't answer something, say what's missing.

            They read your answers on an iPhone. Lead with the answer in a sentence or two, then add only the detail \
            that helps: a few short lines starting with "- " at most. Plain text only: no tables, headings, bold or \
            emoji. Round to whole dollars unless cents matter. Write dates like "Oct 3". Be direct and calm about \
            money: no lectures, no cheerleading.
            """;

    private final AssistantTools tools;
    private final AssistantSettings settings;
    private final ZoneId zone;
    private final List<Tool> definitions;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private AnthropicClient client;
    private String clientKey;
    /** Set if the API ever turns down server-side fallbacks, so one bad header doesn't break every answer. */
    private volatile boolean fallbacksOff;

    /** Blank for Anthropic itself; the tests point it at a stand-in. */
    private final String baseUrl;

    public AssistantService(AssistantTools tools, AssistantSettings settings, AppProperties props,
            @Value("${app.assistant-base-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.tools = tools;
        this.settings = settings;
        this.zone = props.zoneId();
        this.definitions = tools.definitions();
    }

    /** Something to tell the person, as opposed to a bug. */
    public static class Problem extends RuntimeException {
        public Problem(String message) {
            super(message);
        }
    }

    public record Turn(String role, String text, List<String> looked) {
    }

    static final class Conversation {
        final String id;
        final List<MessageParam> history = new ArrayList<>();
        final List<Turn> turns = new ArrayList<>();
        volatile Instant used = Instant.now();

        Conversation(String id) {
            this.id = id;
        }
    }

    public record Reply(String conversationId, String text, List<String> looked) {
    }

    public boolean configured() {
        return settings.apiKey() != null;
    }

    /** The conversation so far, or null if it's gone (the app restarted, or it's old). */
    public List<Turn> transcript(String id) {
        Conversation c = conversations.get(id);
        if (c == null) {
            return null;
        }
        synchronized (c) {
            return List.copyOf(c.turns);
        }
    }

    public void forget(String id) {
        conversations.remove(id);
    }

    public Reply ask(String conversationId, String question) {
        String key = settings.apiKey();
        if (key == null) {
            throw new Problem("Add an Anthropic API key first.");
        }
        AnthropicClient api = client(key);
        Conversation c = conversationId == null ? null : conversations.get(conversationId);
        if (c == null) {
            c = start();
        }
        synchronized (c) {
            c.used = Instant.now();
            String model = settings.model();
            int before = c.history.size();
            LocalDate today = LocalDate.now(zone);
            c.history.add(MessageParam.ofUser("Today is "
                    + today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)) + ".\n\n" + question));
            Set<String> looked = new LinkedHashSet<>();
            long cost = 0;
            try {
                for (int step = 0; ; step++) {
                    boolean last = step == MAX_STEPS - 1;
                    Message r = create(api, model, c.history, last);
                    cost += microdollars(r, model);
                    c.history.add(r.toParam());
                    StopReason stop = r.stopReason().orElse(null);
                    if (StopReason.TOOL_USE.equals(stop) && !last) {
                        c.history.add(MessageParam.ofUserOfContentBlockParams(runTools(r, looked)));
                        continue;
                    }
                    String text = text(r);
                    if (StopReason.REFUSAL.equals(stop)) {
                        throw new Problem("Claude didn't answer that one. Try asking it another way.");
                    }
                    if (StopReason.MAX_TOKENS.equals(stop)) {
                        text = text + "\n\n(The answer was cut off. Ask for less at once.)";
                    }
                    if (text.isBlank()) {
                        throw new Problem("No answer came back. Try again.");
                    }
                    c.turns.add(new Turn("user", question, List.of()));
                    c.turns.add(new Turn("assistant", text.strip(), List.copyOf(looked)));
                    return new Reply(c.id, text.strip(), List.copyOf(looked));
                }
            } catch (RuntimeException e) {
                // Leave the conversation as it was before this question, so the next one
                // doesn't start from half a turn (a tool call with no answer).
                c.history.subList(before, c.history.size()).clear();
                throw e instanceof Problem ? e : problem(e);
            } finally {
                settings.addUsage(YearMonth.now(zone), 1, cost);
            }
        }
    }

    /** Checks a key works (and can use the chosen model) before it's saved. */
    public void check(String key, String model) {
        AnthropicClient probe = builder(key).maxRetries(1).timeout(Duration.ofSeconds(20)).build();
        try {
            probe.models().retrieve(model);
        } catch (NotFoundException e) {
            throw new Problem("That key works, but can't use " + model + ". Pick another model.");
        } catch (RuntimeException e) {
            throw problem(e);
        } finally {
            probe.close();
        }
    }

    // --- The loop ---------------------------------------------------------------------

    private Message create(AnthropicClient api, String model, List<MessageParam> history, boolean last) {
        MessageCreateParams.Builder p = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(SYSTEM).build()))
                .messages(List.copyOf(history))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                // Caches everything up to the newest message: the system prompt and tools on
                // every question, and the conversation so far within one.
                .cacheControl(CacheControlEphemeral.builder().build());
        definitions.forEach(p::addTool);
        if (last) {
            p.toolChoice(ToolChoiceNone.builder().build());
        }
        // A declined answer is retried server-side on the model Anthropic picks for that
        // kind of decline. Haiku has no fallback.
        boolean fallbacks = !fallbacksOff && !model.startsWith("claude-haiku");
        if (fallbacks) {
            p.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        try {
            return api.messages().create(p.build());
        } catch (BadRequestException e) {
            if (fallbacks && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("fallback")) {
                log.warn("Server-side fallbacks were refused; asking without them from now on: {}", e.getMessage());
                fallbacksOff = true;
                return create(api, model, history, last);
            }
            throw e;
        }
    }

    private List<ContentBlockParam> runTools(Message r, Set<String> looked) {
        // Every result goes back in one message, in the order they were asked for.
        List<ContentBlockParam> results = new ArrayList<>();
        for (ContentBlock block : r.content()) {
            if (block.toolUse().isEmpty()) {
                continue;
            }
            ToolUseBlock use = block.toolUse().get();
            looked.add(LOOKED.getOrDefault(use.name(), use.name()));
            String out;
            boolean error = false;
            try {
                JsonNode input = use._input().convert(JsonNode.class);
                out = tools.run(use.name(), input == null ? JsonNodeFactory.instance.objectNode() : input);
            } catch (AssistantTools.BadInput e) {
                out = e.getMessage();
                error = true;
            } catch (RuntimeException e) {
                log.warn("Assistant tool {} failed", use.name(), e);
                out = "That lookup failed inside the app: " + e.getClass().getSimpleName();
                error = true;
            }
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(use.id()).content(out).isError(error).build()));
        }
        return results;
    }

    /** How each lookup is described under an answer. */
    private static final Map<String, String> LOOKED = Map.of(
            "find_transactions", "transactions",
            "monthly_summary", "monthly totals",
            "this_month", "this month",
            "spending_targets_and_goals", "targets and goals",
            "monthly_expenses", "monthly expenses",
            "rent_and_loans", "rent and loans",
            "paychecks", "paychecks",
            "list_categories_and_accounts", "categories");

    private static String text(Message r) {
        StringBuilder out = new StringBuilder();
        for (ContentBlock b : r.content()) {
            b.text().ifPresent(t -> {
                if (!out.isEmpty()) {
                    out.append("\n\n");
                }
                out.append(t.text());
            });
        }
        return out.toString();
    }

    // --- Plumbing ---------------------------------------------------------------------

    private synchronized AnthropicClient client(String key) {
        if (client == null || !key.equals(clientKey)) {
            if (client != null) {
                client.close();
            }
            client = builder(key).maxRetries(2).timeout(Duration.ofSeconds(120)).build();
            clientKey = key;
        }
        return client;
    }

    private AnthropicOkHttpClient.Builder builder(String key) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(key);
        return baseUrl == null || baseUrl.isBlank() ? b : b.baseUrl(baseUrl);
    }

    private Conversation start() {
        Instant cutoff = Instant.now().minus(FORGET_AFTER);
        conversations.values().removeIf(c -> c.used.isBefore(cutoff));
        while (conversations.size() >= MAX_CONVERSATIONS) {
            conversations.values().stream().min((a, b) -> a.used.compareTo(b.used))
                    .ifPresent(oldest -> conversations.remove(oldest.id));
        }
        byte[] id = new byte[12];
        random.nextBytes(id);
        Conversation c = new Conversation(HexFormat.of().formatHex(id));
        conversations.put(c.id, c);
        return c;
    }

    private static long microdollars(Message r, String requested) {
        AssistantSettings.Price price = AssistantSettings.PRICES.getOrDefault(r.model().asString(),
                AssistantSettings.PRICES.get(requested));
        if (price == null) {
            return 0;
        }
        Usage u = r.usage();
        // Price is per million tokens, so tokens x price is already in millionths of a dollar.
        return Math.round(u.inputTokens() * price.input() + u.outputTokens() * price.output()
                + u.cacheReadInputTokens().orElse(0L) * price.cacheRead()
                + u.cacheCreationInputTokens().orElse(0L) * price.cacheWrite());
    }

    private static Problem problem(RuntimeException e) {
        if (e instanceof UnauthorizedException) {
            return new Problem("Anthropic didn't accept the API key. Check it under Settings below.");
        }
        if (e instanceof PermissionDeniedException) {
            return new Problem("That API key isn't allowed to do this. Check its workspace in the Anthropic console.");
        }
        if (e instanceof RateLimitException) {
            return new Problem("Too many questions at once for this API key. Try again in a minute.");
        }
        if (e instanceof InternalServerException) {
            return new Problem("Claude is busy right now. Try again in a minute.");
        }
        if (e instanceof AnthropicIoException) {
            return new Problem("Couldn't reach Anthropic from the server. Is it online?");
        }
        if (e instanceof AnthropicServiceException s) {
            String msg = String.valueOf(s.getMessage());
            if (msg.toLowerCase(Locale.ROOT).contains("credit balance")) {
                return new Problem("The Anthropic account is out of credit. Add some in the Anthropic console.");
            }
            log.warn("Assistant request failed", e);
            return new Problem("Claude couldn't answer (" + s.statusCode() + "). Try again.");
        }
        log.warn("Assistant failed", e);
        return new Problem("Something went wrong asking Claude. Try again.");
    }
}
