package com.example.support.security;

import com.example.support.config.PlatformProperties;
import com.example.support.web.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** HS256 with a shared dev secret keeps the repo self-contained. Production would use the IdP's JWKS (RS256/ES256). */
    @Bean
    JwtDecoder jwtDecoder(PlatformProperties props) {
        var key = new SecretKeySpec(props.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(props.security().audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.security().issuer()), audience));
        return decoder;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper, PlatformProperties props, RateLimiter limiter)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> Problems.write(mapper, req, res,
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                "Send a valid bearer token.", Map.of("WWW-Authenticate", "Bearer"));
        AccessDeniedHandler forbidden = (req, res, ex) -> Problems.write(mapper, req, res,
                HttpStatus.FORBIDDEN, "forbidden", "Insufficient scope",
                "Your token does not grant access to this operation.", Map.of());

        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/mock/**", "/openapi.yaml", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/customers/*").hasAuthority("SCOPE_customers:read")
                        .requestMatchers(HttpMethod.GET, "/v1/cases", "/v1/cases/*").hasAuthority("SCOPE_cases:read")
                        .requestMatchers(HttpMethod.POST, "/v1/cases/*/comments").hasAuthority("SCOPE_cases:write")
                        .requestMatchers(HttpMethod.POST, "/v1/cases/*/escalate").hasAuthority("SCOPE_cases:escalate")
                        .requestMatchers("/v1/webhook-subscriptions", "/v1/webhook-subscriptions/*").hasAuthority("SCOPE_webhooks:admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .addFilterAfter(new RateLimitFilter(limiter, mapper, props.rateLimit().perMinute(),
                        props.rateLimit().agentPerMinute()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
