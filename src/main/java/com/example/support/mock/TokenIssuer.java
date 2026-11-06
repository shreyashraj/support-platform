package com.example.support.mock;

import com.example.support.config.PlatformProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Stand-in for the corporate IdP. Each client id is a persona with fixed scopes. */
@Component
@MockConditional
public class TokenIssuer {
    public record Persona(String subject, String scope, List<String> accounts) {}

    public static final Map<String, Persona> PERSONAS = Map.of(
            "support-agent", new Persona("alice.support", "customers:read cases:read cases:write cases:escalate", null),
            "support-readonly", new Persona("bob.readonly", "customers:read cases:read", null),
            "finance-analyst", new Persona("fiona.finance", "customers:read finance:read cases:read", null),
            "csm-limited", new Persona("carl.csm", "customers:read cases:read cases:write", List.of("ACC-003", "ACC-007")),
            "platform-admin", new Persona("root.admin",
                    "customers:read finance:read cases:read cases:write cases:escalate webhooks:admin", null));

    private final PlatformProperties props;
    private final Clock clock;

    public TokenIssuer(PlatformProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public Optional<Persona> persona(String clientId) {
        return Optional.ofNullable(PERSONAS.get(clientId));
    }

    public String issue(String clientId, Duration ttl) {
        Persona p = PERSONAS.get(clientId);
        if (p == null) throw new IllegalArgumentException("unknown client " + clientId);
        long now = clock.millis();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(props.security().issuer())
                .subject(p.subject())
                .audience(props.security().audience())
                .issueTime(new Date(now))
                .expirationTime(new Date(now + ttl.toMillis()))
                .claim("scope", p.scope())
                .claim("client_id", clientId);
        if (p.accounts() != null) claims.claim("accounts", p.accounts());
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(props.security().jwtSecret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
