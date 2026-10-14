package cloud.gehan.finance.web;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.config.AppProperties;
import cloud.gehan.finance.service.HistoryService;
import cloud.gehan.finance.service.Settings;

@RestController
@RequestMapping("/api")
public class HistoryController {

    private final HistoryService history;
    private final Settings settings;
    private final AppProperties props;

    public HistoryController(HistoryService history, Settings settings, AppProperties props) {
        this.history = history;
        this.settings = settings;
        this.props = props;
    }

    @GetMapping("/history")
    public List<HistoryService.MonthScore> months(@RequestParam(defaultValue = "12") int count) {
        return history.months(Math.max(1, Math.min(count, 36)));
    }

    /** Defaults to last month: the one that's finished. */
    @GetMapping("/history/recap")
    public HistoryService.Recap recap(@RequestParam(required = false) String month) {
        YearMonth m = month == null || month.isBlank() ? YearMonth.now(props.zoneId()).minusMonths(1) : YearMonth.parse(month);
        return history.recap(m);
    }

    @GetMapping("/history/days")
    public HistoryService.CalendarMonth days(@RequestParam(required = false) String month) {
        return history.calendar(month == null || month.isBlank() ? YearMonth.now(props.zoneId()) : YearMonth.parse(month));
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> out = new HashMap<>();
        out.put("savingsTargetCents", settings.savingsTargetCents());
        return out;
    }

    public record SettingsBody(Long savingsTargetCents) {
    }

    @PutMapping("/settings")
    public Map<String, Object> update(@RequestBody SettingsBody body) {
        settings.setSavingsTargetCents(body.savingsTargetCents());
        return settings();
    }
}
