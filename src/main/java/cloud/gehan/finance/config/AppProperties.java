package cloud.gehan.finance.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        String username,
        String password,
        int rememberMeDays,
        String clientIpHeader,
        String dataDir,
        String encryptionKey,
        boolean demoData,
        String zone,
        boolean secureCookies) {

    public ZoneId zoneId() {
        return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    }
}
