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
import cloud.gehan.finance.service.Budget;
import cloud.gehan.finance.service.FlowService;
import cloud.gehan.finance.service.SummaryService;

@RestController
public class OverviewController {

    private final SummaryService summary;
    private final FlowService flow;
    private final AppProperties props;
    private final Budget budget;

    public OverviewController(SummaryService summary, FlowService flow, AppProperties props, Budget budget) {
        this.summary = summary;
        this.flow = flow;
        this.props = props;
        this.budget = budget;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Principal principal) {
        Map<String, Object> me = new HashMap<>();
        me.put("username", principal.getName());
        me.put("demo", props.demoData());
        me.put("today", LocalDate.now(props.zoneId()).toString());
        // With amounts hidden, money shows as a share of this: a month of take-home pay.
        me.put("monthlyPayCents", budget.expectedIncome(YearMonth.now(props.zoneId())));
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
