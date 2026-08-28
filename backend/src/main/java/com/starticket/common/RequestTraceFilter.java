package com.starticket.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern BUSINESS_PATH = Pattern.compile(
            "/(?:orders|payments|tickets|events)/([^/?]+)(?:/|$)");
    private static final Set<String> NON_BUSINESS_PATHS = Set.of("callback", "pending");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        MDC.put("user", "anonymous");
        MDC.put("businessNo", businessNo(request));
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("request completed method={} path={} status={} durationMs={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.clear();
        }
    }

    public static String currentRequestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    private static String requestId(String candidate) {
        return candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private static String businessNo(HttpServletRequest request) {
        Matcher matcher = BUSINESS_PATH.matcher(request.getRequestURI());
        if (matcher.find() && !NON_BUSINESS_PATHS.contains(matcher.group(1))) return matcher.group(1);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        return idempotencyKey == null || idempotencyKey.isBlank() ? "-" : idempotencyKey.substring(0,
                Math.min(idempotencyKey.length(), 100));
    }
}
