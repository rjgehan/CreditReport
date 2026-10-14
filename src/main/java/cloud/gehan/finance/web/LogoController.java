package cloud.gehan.finance.web;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import cloud.gehan.finance.service.MerchantLogos;

@RestController
public class LogoController {

    private final MerchantLogos logos;

    public LogoController(MerchantLogos logos) {
        this.logos = logos;
    }

    /** A merchant's icon, from this server's cache. Unknown or missing icons are a 404 the page falls back from. */
    @GetMapping("/api/logos/{domain:.+}")
    public ResponseEntity<byte[]> logo(@PathVariable String domain) {
        return logos.icon(domain)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.maxAge(Duration.ofDays(1))).build());
    }
}
