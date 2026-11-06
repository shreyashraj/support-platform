package com.example.support.security;

import com.example.support.web.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Not a @Component on purpose: it must run inside the security chain, after the token has been read. */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final ObjectMapper mapper;
    private final int perMinute;
    private final int agentPerMinute;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper mapper, int perMinute, int agentPerMinute) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.perMinute = perMinute;
        this.agentPerMinute = agentPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !req.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(req, res);
            return;
        }
        String agent = req.getHeader("X-Agent-Id");
        int limit = agent == null ? perMinute : agentPerMinute;
        var d = limiter.tryAcquire(auth.getName() + "|" + (agent == null ? "-" : agent), limit);
        res.setHeader("RateLimit-Limit", String.valueOf(d.limit()));
        res.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
        res.setHeader("RateLimit-Reset", String.valueOf(d.resetSeconds()));
        if (!d.allowed()) {
            Problems.write(mapper, req, res, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Rate limit exceeded",
                    "Too many requests. Retry in " + d.retryAfterSeconds() + "s.",
                    Map.of("Retry-After", String.valueOf(d.retryAfterSeconds())));
            return;
        }
        chain.doFilter(req, res);
    }
}
