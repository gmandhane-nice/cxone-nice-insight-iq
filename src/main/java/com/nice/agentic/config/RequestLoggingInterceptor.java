package com.nice.agentic.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Interceptor that generates a unique request ID for every incoming request,
 * logs method/path/status/duration, and adds the X-Request-Id response header.
 */
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    private static final String ATTR_REQUEST_ID = "X-Request-Id";
    private static final String ATTR_START_TIME = "X-Request-Start";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        request.setAttribute(ATTR_START_TIME, System.currentTimeMillis());
        response.setHeader("X-Request-Id", requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String requestId = (String) request.getAttribute(ATTR_REQUEST_ID);
        Long startTime = (Long) request.getAttribute(ATTR_START_TIME);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;
        int status = response.getStatus();

        log.info("[{}] {} {} -> {} ({}ms)",
                requestId, request.getMethod(), request.getRequestURI(), status, duration);
    }
}
