package cloud.gehan.finance.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cloud.gehan.finance.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;

/**
 * This page is on the internet and guards bank data, so a password guesser gets
 * five tries per address every fifteen minutes and then waits.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final String ipHeader;
    private final Clock clock;

    @Autowired
    public LoginThrottle(AppProperties props) {
        this(props.clientIpHeader(), Clock.systemUTC());
    }

    LoginThrottle(String ipHeader, Clock clock) {
        this.ipHeader = ipHeader;
        this.clock = clock;
    }

    public String clientAddress(HttpServletRequest request) {
        if (ipHeader != null && !ipHeader.isBlank()) {
            String value = request.getHeader(ipHeader);
            if (value != null && !value.isBlank()) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    public boolean blocked(String address) {
        Deque<Instant> recent = failures.get(address);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= MAX_FAILURES;
        }
    }

    public void failed(String address) {
        Deque<Instant> recent = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
        // Keep a flood of distinct addresses from growing the map without bound.
        if (failures.size() > 10_000) {
            failures.clear();
        }
    }

    public void succeeded(String address) {
        failures.remove(address);
    }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
    }
}
