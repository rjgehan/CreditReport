package cloud.gehan.finance.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.service.BillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Rent, loans and the other same-every-month bills. */
@RestController
@RequestMapping("/api")
public class BillController {

    private final BillService bills;

    public BillController(BillService bills) {
        this.bills = bills;
    }

    @GetMapping("/bills")
    public BillService.Bills list(@RequestParam(defaultValue = "12") int months) {
        return bills.bills(months);
    }

    /** @param amountCents what was paid each time, as a positive number */
    public record PaymentsBody(@NotNull Long accountId, long amountCents, @NotEmpty List<LocalDate> days,
            String description) {
    }

    @PostMapping("/bills/{categoryId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addPayments(@PathVariable Long categoryId, @RequestBody @Valid PaymentsBody body) {
        return Map.of("added", bills.addPayments(categoryId, body.accountId(), body.amountCents(), body.days(),
                body.description()));
    }
}
