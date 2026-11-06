package com.example.support.mock;

import com.example.support.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Plays the part of each upstream's gateway: API key check plus injected faults. */
@Component
@MockConditional
class UpstreamGateFilter extends OncePerRequestFilter {
    private final Chaos chaos;
    private final String apiKey;

    UpstreamGateFilter(Chaos chaos, PlatformProperties props) {
        this.chaos = chaos;
        this.apiKey = props.upstream().apiKey();
    }

    private static String target(String path) {
        if (path.startsWith("/mock/cases/")) return "cases";
        if (path.startsWith("/mock/entitlements/")) return "entitlements";
        if (path.startsWith("/mock/usage/")) return "usage";
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String target = target(req.getRequestURI());
        if (target == null) {
            chain.doFilter(req, res);
            return;
        }
        if (!apiKey.equals(req.getHeader("X-Upstream-Key"))) {
            res.setStatus(401);
            return;
        }
        if (chaos.apply(target)) {
            res.setStatus(503);
            return;
        }
        chain.doFilter(req, res);
    }
}
