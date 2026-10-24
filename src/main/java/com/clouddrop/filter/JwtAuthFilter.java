package com.clouddrop.filter;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.JwtService;
import com.clouddrop.dto.ErrorResponse;
import com.clouddrop.exception.InvalidTokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every controller. DEFAULT-DENY: every request needs a valid "Authorization: Bearer <jwt>"
 * unless it is on the explicit public list below. (A "protect /api/** only" rule is easy to bypass with
 * odd URLs such as "/api;x/files", so we protect everything and whitelist instead.)
 * On success the identity goes into AuthContext; it is ALWAYS cleared in finally.
 * Registered in FilterConfig (not with @Component, to avoid being registered twice).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER = "Bearer ";

    // "METHOD path" pairs that need no token. Exact matches only.
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "GET /api/health");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!isPublic(request)) {
                String header = request.getHeader("Authorization");
                if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                    reject(request, response, "Authentication required");
                    return;
                }
                try {
                    AuthContext.set(jwtService.parse(header.substring(BEARER.length()).trim()));
                } catch (InvalidTokenException e) {
                    log.debug("Rejected token: {}", e.getMessage());
                    reject(request, response, "Invalid or expired token");   // same text for every cause
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (PUBLIC_ENDPOINTS.contains(method + " " + path)) {
            return true;
        }
        // Swagger UI and the OpenAPI document (dev convenience; disable in production)
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return true;
        }
        // Public share links: GET /api/shares/{token} and GET /api/shares/{token}/download. Nothing else.
        String sharePrefix = "/api/shares/";
        if ("GET".equals(method) && path.startsWith(sharePrefix)) {
            String[] parts = path.substring(sharePrefix.length()).split("/", -1);
            return (parts.length == 1 && !parts[0].isEmpty())
                    || (parts.length == 2 && !parts[0].isEmpty() && parts[1].equals("download"));
        }
        return false;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = new ErrorResponse(Instant.now(), 401, "Unauthorized", message,
                request.getRequestURI(), null);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
