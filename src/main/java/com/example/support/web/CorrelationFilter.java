package com.example.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(Problems.MDC_CORRELATION, id);
        res.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String path = req.getRequestURI();
            if (!path.startsWith("/actuator")) {
                log.info("{} {} -> {} in {}ms sub={} agent={}", req.getMethod(), path, res.getStatus(),
                        (System.nanoTime() - start) / 1_000_000, auth == null ? "-" : auth.getName(),
                        req.getHeader("X-Agent-Id"));
            }
            MDC.remove(Problems.MDC_CORRELATION);
        }
    }
}
