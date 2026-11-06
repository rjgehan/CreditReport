package cloud.gehan.finance.web;

import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.service.ForecastService;
import cloud.gehan.finance.service.FlowService;
import cloud.gehan.finance.service.SummaryService;

@RestController
public class OverviewController {

    private final SummaryService summary;
    private final FlowService flow;
    private final AppProperties props;
    private final ForecastService forecast;

    public OverviewController(SummaryService summary, FlowService flow, AppProperties props, ForecastService forecast) {
        this.summary = summary;
        this.flow = flow;
        this.props = props;
        this.forecast = forecast;
    }

    /** This month looking ahead: what's free to spend once pay due, bills due and savings are counted. */
    @GetMapping("/api/forecast")
    public ForecastService.Forecast forecast() {
        return forecast.forecast();
    }

    /** Changes when the server restarts (an update, say), so an open app knows to reload its screens. */
    static final String STARTED_AT = java.time.Instant.now().toString();

    @GetMapping("/api/me")
    public Map<String, Object> me(Principal principal) {
        Map<String, Object> me = new HashMap<>();
        me.put("startedAt", STARTED_AT);
        me.put("username", principal.getName());
        me.put("demo", props.demoData());
        me.put("today", LocalDate.now(props.zoneId()).toString());
        return me;
    }

    @GetMapping("/api/summary")
    public SummaryService.Home summary(@RequestParam(required = false) String month) {
        return summary.home(month(month));
    }

    @GetMapping("/api/flow")
    public FlowService.Sankey flow(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean average) {
        YearMonth last = month(to);
        YearMonth first = from == null || from.isBlank() ? last : YearMonth.parse(from);
        if (first.isAfter(last)) {
            throw new IllegalArgumentException("from is after to");
        }
        return flow.sankey(first, last, average);
    }

    private YearMonth month(String value) {
        return value == null || value.isBlank() ? YearMonth.now(props.zoneId()) : YearMonth.parse(value);
    }
}
