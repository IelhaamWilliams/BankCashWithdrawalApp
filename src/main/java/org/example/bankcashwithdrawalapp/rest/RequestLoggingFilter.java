package org.example.bankcashwithdrawalapp.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long startNanos = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            if (status >= 500) {
                LOGGER.error("HTTP request completed requestId={} method={} path={} status={} durationMs={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMillis);
            } else if (status >= 400) {
                LOGGER.warn("HTTP request completed requestId={} method={} path={} status={} durationMs={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMillis);
            } else {
                LOGGER.info("HTTP request completed requestId={} method={} path={} status={} durationMs={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMillis);
            }
            MDC.remove("requestId");
        }
    }
}
