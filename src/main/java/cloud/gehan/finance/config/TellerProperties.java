package cloud.gehan.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Teller (teller.io). The development environment is free for up to 100 bank
 * logins and returns real data, which is all one person needs.
 *
 * @param applicationId shown in the Teller dashboard; public, the front end needs it to open Teller Connect
 * @param environment sandbox (fake banks, no certificate needed) or development
 * @param certificate path to certificate.pem from the Teller dashboard
 * @param privateKey path to private_key.pem from the Teller dashboard
 */
@ConfigurationProperties("teller")
public record TellerProperties(String applicationId, String environment, String certificate, String privateKey) {

    public boolean configured() {
        return applicationId != null && !applicationId.isBlank();
    }

    public boolean sandbox() {
        return "sandbox".equalsIgnoreCase(environment);
    }

    public String env() {
        return environment == null || environment.isBlank() ? "development" : environment.toLowerCase();
    }

    public boolean hasCertificate() {
        return certificate != null && !certificate.isBlank() && privateKey != null && !privateKey.isBlank();
    }
}
