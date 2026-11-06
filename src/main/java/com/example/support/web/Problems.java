package com.example.support.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class Problems {
    public static final String TYPE_BASE = "https://support-platform.example.com/problems/";
    public static final String MDC_CORRELATION = "correlationId";

    private Problems() {}

    public static ProblemDetail build(HttpStatus status, String code, String title, String detail, String instance) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(title);
        if (instance != null) pd.setInstance(URI.create(instance));
        pd.setProperty("code", code);
        pd.setProperty("correlationId", correlationId());
        return pd;
    }

    public static String correlationId() {
        String id = MDC.get(MDC_CORRELATION);
        return id == null ? "unknown" : id;
    }

    /** For filters and security handlers, which run outside Spring MVC's exception handling. */
    public static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, HttpStatus status,
            String code, String title, String detail, Map<String, String> headers) throws IOException {
        ProblemDetail pd = build(status, code, title, detail, req.getRequestURI());
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        headers.forEach(res::setHeader);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
