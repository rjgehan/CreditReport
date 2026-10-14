package cloud.gehan.finance.web;

import java.time.LocalDate;
import java.util.Map;

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

import cloud.gehan.finance.model.Cadence;
import cloud.gehan.finance.model.RecurringKind;
import cloud.gehan.finance.service.RecurringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Monthly expenses: subscriptions, bills and other charges that come round on their own. */
@RestController
@RequestMapping("/api/recurring")
public class RecurringController {

    private final RecurringService recurring;

    public RecurringController(RecurringService recurring) {
        this.recurring = recurring;
    }

    @GetMapping
    public RecurringService.Overview overview() {
        return recurring.overview();
    }

    public record HideBody(@NotBlank String key) {
    }

    /** "Not recurring": leave it off the list. */
    @PostMapping("/hide")
    public Map<String, Object> hide(@RequestBody @Valid HideBody body) {
        recurring.hide(body.key());
        return Map.of("ok", true);
    }

    @DeleteMapping("/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void showHidden() {
        recurring.showHidden();
    }

    /** @param amountCents each charge, as a positive amount */
    public record ChargeBody(@NotBlank String name, long amountCents, Cadence cadence, RecurringKind kind,
            LocalDate nextDate) {
    }

    @PostMapping("/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> add(@RequestBody @Valid ChargeBody body) {
        return Map.of("id", recurring.save(null, body.name(), body.amountCents(), body.cadence(), body.kind(),
                body.nextDate()).getId());
    }

    @PutMapping("/charges/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody @Valid ChargeBody body) {
        return Map.of("id", recurring.save(id, body.name(), body.amountCents(), body.cadence(), body.kind(),
                body.nextDate()).getId());
    }

    @DeleteMapping("/charges/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        recurring.delete(id);
    }
}
