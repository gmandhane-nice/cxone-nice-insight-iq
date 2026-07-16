package com.nice.agentic.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler that returns a consistent JSON error response
 * with proper HTTP status codes and correlation/request ID logging.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles IllegalArgumentException — returns 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex,
                                                                 HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Bad request [requestId={}] path={}: {}", requestId, request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Handles RuntimeException with "Snowflake" in the message — returns 503 Service Unavailable.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex,
                                                                       HttpServletRequest request) {
        String requestId = getRequestId(request);

        if (ex.getMessage() != null && ex.getMessage().contains("Snowflake")) {
            log.error("Snowflake unavailable [requestId={}] path={}: {}",
                    requestId, request.getRequestURI(), ex.getMessage(), ex);
            return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
        }

        log.error("Unhandled runtime exception [requestId={}] path={}: {}",
                requestId, request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    /**
     * Catches all remaining unhandled exceptions — returns 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex,
                                                          HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.error("Unhandled exception [requestId={}] path={}: {}",
                requestId, request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message,
                                                               HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message != null ? message : "Unknown error");
        body.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        body.put("path", request.getRequestURI());
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }

    private String getRequestId(HttpServletRequest request) {
        Object requestId = request.getAttribute("X-Request-Id");
        return requestId != null ? requestId.toString() : "unknown";
    }
}
