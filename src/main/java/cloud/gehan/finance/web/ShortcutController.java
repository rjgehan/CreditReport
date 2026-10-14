package cloud.gehan.finance.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.service.ShortcutCategorizer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Endpoints for the iPhone shortcut. /prompt and /answer authenticate with the
 * shortcut's own token instead of a session, since Shortcuts can't sign in;
 * /token and /status are for the signed-in setup screen.
 */
@RestController
@RequestMapping("/api/shortcut")
public class ShortcutController {

    private final ShortcutCategorizer shortcut;

    public ShortcutController(ShortcutCategorizer shortcut) {
        this.shortcut = shortcut;
    }

    /** For the setup screen: whether a token exists and how many transactions are waiting. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("configured", shortcut.configured(), "waiting", shortcut.waiting());
    }

    /** A new token; any shortcut using the old one stops working. */
    @PostMapping("/token")
    public Map<String, Object> token() {
        return Map.of("token", shortcut.newToken());
    }

    @GetMapping(value = "/prompt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> prompt(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!shortcut.authorized(auth)) {
            return denied();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(shortcut.prompt());
    }

    /** A model reply for 40 transactions is a few KB; anything far bigger isn't one. */
    static final int MAX_REPLY_BYTES = 64 * 1024;

    /**
     * Takes the model's reply as the raw request body, whatever type Shortcuts
     * labels it. The token is checked before a byte of the body is read.
     */
    @PostMapping(value = "/answer", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> answer(@RequestHeader(value = "Authorization", required = false) String auth,
            HttpServletRequest request) throws IOException {
        if (!shortcut.authorized(auth)) {
            return denied();
        }
        if (request.getContentLengthLong() > MAX_REPLY_BYTES) {
            return tooBig();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_REPLY_BYTES + 1);
        if (body.length > MAX_REPLY_BYTES) {
            return tooBig();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(shortcut.answer(new String(body, StandardCharsets.UTF_8)));
    }

    private static ResponseEntity<String> tooBig() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.TEXT_PLAIN)
                .body("That reply is too long to be the model's answer.");
    }

    private static ResponseEntity<String> denied() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.TEXT_PLAIN)
                .body("The shortcut's token is missing or out of date. Make a new one in the app: Accounts, iPhone shortcut.");
    }
}
