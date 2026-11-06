package com.example.support.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * Who the agent is acting for. The claims are read WITHOUT verifying the signature: they are only used to label the
 * audit log and to fail fast on a missing scope. The API verifies the token on every call and is the only authority.
 */
record Identity(String subject, Set<String> scopes) {

    static Identity fromToken(String token) {
        try {
            String payload = token.split("\\.")[1];
            var claims = Json.parse(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
            String scope = Json.str(claims.get("scope"));
            return new Identity(Json.str(claims.get("sub")) == null ? "unknown" : Json.str(claims.get("sub")),
                    scope == null ? Set.of() : Set.of(scope.split(" ")));
        } catch (RuntimeException e) {
            return new Identity("unknown", Set.of());
        }
    }

    boolean can(String scope) {
        return scopes.contains(scope);
    }
}
