package com.example.support.security;

import com.example.support.domain.AccountIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public record Caller(String subject, String agent, AccessScope scope, boolean finance) {

    public static Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new IllegalStateException("no authenticated caller");
        }
        List<String> accounts = jwt.getToken().getClaimAsStringList("accounts");
        AccessScope scope = AccessScope.ALL;
        if (accounts != null) {
            Set<String> ids = new HashSet<>();
            accounts.forEach(a -> ids.add(AccountIds.normalize(a)));
            scope = new AccessScope(ids);
        }
        boolean finance = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_finance:read"));
        return new Caller(jwt.getName(), agentHeader(), scope, finance);
    }

    private static String agentHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            HttpServletRequest req = sra.getRequest();
            return req.getHeader("X-Agent-Id");
        }
        return null;
    }
}
