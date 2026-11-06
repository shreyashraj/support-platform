package com.example.support.web;

import com.example.support.upstream.UpstreamClientException;
import com.example.support.upstream.UpstreamUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<Object> problem(ProblemException ex, HttpServletRequest req) {
        ProblemDetail pd = Problems.build(ex.status(), ex.code(), ex.title(), ex.getMessage(), req.getRequestURI());
        ex.extras().forEach(pd::setProperty);
        return respond(pd, new HttpHeaders());
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<Object> upstreamDown(UpstreamUnavailableException ex, HttpServletRequest req) {
        HttpStatus status;
        String code;
        switch (ex.kind()) {
            case TIMEOUT -> { status = HttpStatus.GATEWAY_TIMEOUT; code = "upstream-timeout"; }
            case CIRCUIT_OPEN -> { status = HttpStatus.SERVICE_UNAVAILABLE; code = "upstream-unavailable"; }
            default -> { status = HttpStatus.BAD_GATEWAY; code = "upstream-error"; }
        }
        ProblemDetail pd = Problems.build(status, code, "Upstream system problem",
                "The " + ex.upstream() + " system could not serve this request.", req.getRequestURI());
        pd.setProperty("upstream", ex.upstream());
        HttpHeaders headers = new HttpHeaders();
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            headers.set(HttpHeaders.RETRY_AFTER, "10");
            pd.setProperty("retryAfterSeconds", 10);
        }
        return respond(pd, headers);
    }

    @ExceptionHandler(UpstreamClientException.class)
    ResponseEntity<Object> upstreamRejected(UpstreamClientException ex, HttpServletRequest req) {
        log.warn("upstream rejected request: status={} body={}", ex.status(), ex.body());
        ProblemDetail pd = Problems.build(HttpStatus.BAD_GATEWAY, "upstream-error", "Upstream system problem",
                "An upstream system rejected the request.", req.getRequestURI());
        return respond(pd, new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest req) {
        log.error("unhandled exception on {}", req.getRequestURI(), ex);
        ProblemDetail pd = Problems.build(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Something went wrong. Quote the correlationId when reporting it.", req.getRequestURI());
        return respond(pd, new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())));
        }
        return validation("The request body is invalid.", errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> errors.add(Map.of(
                "field", r.getMethodParameter().getParameterName() == null ? "?" : r.getMethodParameter().getParameterName(),
                "message", String.valueOf(e.getDefaultMessage())))));
        return validation("One or more parameters are invalid.", errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation("The request body is missing or not valid JSON for this operation.", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(org.springframework.beans.TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        return validation("Parameter '" + field + "' has an invalid value.",
                List.of(Map.of("field", String.valueOf(field), "message", "invalid value '" + ex.getValue() + "'")), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validation("Required parameter '" + ex.getParameterName() + "' is missing.", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && !pd.getProperties().containsKey("code")) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            String code = status == HttpStatus.NOT_FOUND ? "not-found"
                    : status == HttpStatus.METHOD_NOT_ALLOWED ? "method-not-allowed"
                    : status == HttpStatus.NOT_ACCEPTABLE ? "not-acceptable"
                    : status == HttpStatus.UNSUPPORTED_MEDIA_TYPE ? "unsupported-media-type" : "bad-request";
            String path = request instanceof ServletWebRequest s ? s.getRequest().getRequestURI() : null;
            ProblemDetail fixed = Problems.build(status, code, status.getReasonPhrase(), pd.getDetail(), path);
            return respond(fixed, headers);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validation(String detail, List<Map<String, String>> errors, WebRequest request) {
        String path = request instanceof ServletWebRequest s ? s.getRequest().getRequestURI() : null;
        ProblemDetail pd = Problems.build(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed", detail, path);
        if (!errors.isEmpty()) pd.setProperty("errors", errors);
        return respond(pd, new HttpHeaders());
    }

    private ResponseEntity<Object> respond(ProblemDetail pd, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, headers, HttpStatus.valueOf(pd.getStatus()));
    }
}
