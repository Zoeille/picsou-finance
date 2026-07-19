package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    /**
     * Whether to send {@code Strict-Transport-Security}. Off by default, and it must stay in
     * lock-step with the nginx-side gate (docker/entrypoint.sh writes
     * {@code /etc/nginx/snippets/picsou-hsts.conf} from the same {@code HSTS_ENABLED}).
     *
     * <p>Gating this matters because Spring Security's {@code HstsHeaderWriter} fires on any
     * request where {@code isSecure()} is true, and {@code forward-headers-strategy: framework}
     * makes that true from {@code X-Forwarded-Proto: https}. Without the gate, every
     * {@code /api/*} response carries HSTS even when nginx has been told not to — and the SPA
     * calls the API on load, so the browser pins the policy regardless. With a locally-issued
     * certificate that is a lockout: the browser then refuses the "proceed anyway" bypass and
     * there is no in-app recovery.
     *
     * <p>Bound as a {@code String} and parsed leniently on purpose. A primitive {@code boolean}
     * would make Spring fail field injection on any value it cannot convert — including a bare
     * {@code HSTS_ENABLED=} — which takes the whole backend down under supervisord while nginx
     * keeps serving, turning a harmless typo into an app-wide 502. {@code docker/entrypoint.sh}
     * deliberately tolerates the same inputs and warns; the two must agree.
     */
    @Value("${app.hsts-enabled:false}")
    private String hstsEnabledRaw;

    /** Mirrors the {@code true|1|yes|on} set accepted by {@code docker/entrypoint.sh}. */
    private boolean hstsEnabled() {
        String v = hstsEnabledRaw == null ? "" : hstsEnabledRaw.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtUtil jwtUtil,
                                           AppUserRepository appUserRepository,
                                           SetupFilter setupFilter,
                                           PersistentSessionService persistentSessionService,
                                           AuthCookieWriter authCookieWriter,
                                           MfaService mfaService,
                                           AccessKeyService accessKeyService,
                                           @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())   // stateless JWT + SameSite cookies cover this
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .httpStrictTransportSecurity(hsts -> {
                    if (hstsEnabled()) {
                        hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
                    } else {
                        hsts.disable();
                    }
                })
                .referrerPolicy(rp -> rp
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/setup/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mfa/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/activate/*").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().authenticated()
            )
            // All custom filters anchor to UsernamePasswordAuthenticationFilter because
            // Spring Security's FilterOrderRegistration only knows the order of its own
            // well-known filter classes — passing a custom class as anchor throws
            // "does not have a registered order". SetupFilter returns 503/410 before
            // setup is complete; on a fresh install no JWT cookie exists anyway. The
            // PersistentTokenAuthFilter must run AFTER JwtAuthenticationFilter so an
            // active access cookie short-circuits and we don't pay the DB hit per
            // request — registration order below preserves that ordering.
            .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, appUserRepository), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(
                new PersistentTokenAuthFilter(persistentSessionService, appUserRepository, jwtUtil, authCookieWriter, mfaService),
                UsernamePasswordAuthenticationFilter.class
            )
            // Last of the same-anchor filters: only acts on /mcp/** (shouldNotFilter), validates the
            // Bearer access-key, and sets an AccessKeyAuthentication carrying scope authorities only.
            .addFilterBefore(
                new AccessKeyAuthFilter(accessKeyService, mcpKeyBuckets),
                UsernamePasswordAuthenticationFilter.class
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(401);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("""
                        {"status":401,"title":"Unauthorized","detail":"Authentication required"}
                        """);
                })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(new LoggingCorsProcessor());
        return filter;
    }

    /**
     * Dynamic CORS: reads {@code cors.allowed-origins} from {@code app_setting}
     * per request, so changes made through the setup wizard's Security step
     * take effect without a container restart. Falls back to the env var for
     * fresh installs (and for env-only operators who never run the wizard).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppSettingRepository settingRepository) {
        return new DynamicCorsConfigurationSource(settingRepository, allowedOrigins);
    }
}
