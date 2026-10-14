package cloud.gehan.finance.config;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import cloud.gehan.finance.security.LoginThrottle;
import cloud.gehan.finance.security.SpaCsrfTokenRequestHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(AppProperties props, PasswordEncoder encoder) {
        String password = props.password();
        String stored;
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("APP_PASSWORD is not set. Sign in as '{}' with this generated password: {}  "
                    + "(it changes on every restart; set APP_PASSWORD to keep one)", props.username(), generated);
            stored = encoder.encode(generated);
        } else if (password.startsWith("{")) {
            stored = password;
        } else {
            stored = encoder.encode(password);
        }
        return new InMemoryUserDetailsManager(User.withUsername(props.username()).password(stored).roles("OWNER").build());
    }

    /**
     * Remember-me tokens live in the database, not in a cookie signed with the
     * password hash: a plain-text APP_PASSWORD is re-hashed with a fresh salt on
     * every start, which would sign the phone out after every deploy.
     */
    @Bean
    PersistentTokenRepository rememberMeTokens(DataSource dataSource) {
        new JdbcTemplate(dataSource).execute("create table if not exists persistent_logins ("
                + "username varchar(64) not null, series varchar(64) primary key, "
                + "token varchar(64) not null, last_used timestamp not null)");
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(dataSource);
        return repo;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppProperties props, LoginThrottle throttle,
            PersistentTokenRepository tokens, UserDetailsService users) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login.html", "/login", "/assets/**", "/icons/**",
                                "/manifest.webmanifest", "/favicon.ico", "/robots.txt",
                                "/actuator/health", "/error"
                        ).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((req, res, auth) -> {
                            throttle.succeeded(throttle.clientAddress(req));
                            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                        .failureHandler((req, res, ex) -> {
                            throttle.failed(throttle.clientAddress(req));
                            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                        })
                        .permitAll())
                .rememberMe(remember -> remember
                        .tokenRepository(tokens)
                        .userDetailsService(users)
                        .key("finance-remember-me")
                        .rememberMeCookieName("finance-remember")
                        .useSecureCookie(props.secureCookies())
                        .tokenValiditySeconds((int) Duration.ofDays(props.rememberMeDays()).toSeconds()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies("JSESSIONID", "finance-remember")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // The API answers 401 so the front end can send you to the login page
                // itself; a redirect would just hand fetch() the login HTML.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/")))
                .addFilterBefore(new ThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                // Teller Connect is the one third-party script, loaded only when linking a bank.
                                "script-src 'self' https://cdn.teller.io",
                                "frame-src https://teller.io https://*.teller.io",
                                "connect-src 'self' https://*.teller.io",
                                "style-src 'self' 'unsafe-inline'",
                                "img-src 'self' data:",
                                "font-src 'self'",
                                "form-action 'self'",
                                "frame-ancestors 'none'",
                                "base-uri 'self'",
                                "object-src 'none'")))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /** Refuses a login attempt outright while its address is cooling off. */
    static final class ThrottleFilter extends OncePerRequestFilter {
        private final LoginThrottle throttle;

        ThrottleFilter(LoginThrottle throttle) {
            this.throttle = throttle;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !("POST".equals(request.getMethod()) && "/login".equals(request.getServletPath()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (throttle.blocked(throttle.clientAddress(request))) {
                response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
