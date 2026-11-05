package cloud.gehan.finance.web;

import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cloud.gehan.finance.provider.ProviderException;

/** Turns failures into {"error": "..."} the front end can show as-is. */
@RestControllerAdvice(basePackages = "cloud.gehan.finance.web")
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<Map<String, String>> provider(ProviderException e) {
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        String message = e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null
                ? m.getBindingResult().getFieldError().getField() + " " + m.getBindingResult().getFieldError().getDefaultMessage()
                : e.getMessage();
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        log.warn("Request failed: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
