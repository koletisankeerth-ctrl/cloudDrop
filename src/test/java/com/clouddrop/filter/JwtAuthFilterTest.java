package com.clouddrop.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.AuthenticatedUser;
import com.clouddrop.auth.JwtService;
import com.clouddrop.config.AppProperties;
import com.clouddrop.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthFilterTest {

    static final String SECRET = "unit-test-secret-that-is-at-least-32-chars-long";

    JwtService jwtService = jwtService(Clock.systemUTC());
    JwtAuthFilter filter = new JwtAuthFilter(jwtService, new ObjectMapper().findAndRegisterModules());

    @Test
    void missingAuthorizationHeader_returns401() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/files", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("Authentication required");
    }

    @Test
    void invalidToken_returns401() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/files", "Bearer not-a-jwt");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token");
    }

    @Test
    void expiredToken_returns401() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
        String expired = jwtService(past).generate(user()).token();

        assertThat(run("GET", "/api/files", "Bearer " + expired).getStatus()).isEqualTo(401);
    }

    @Test
    void nonBearerScheme_returns401() throws Exception {
        assertThat(run("GET", "/api/files", "Basic abc123").getStatus()).isEqualTo(401);
    }

    @Test
    void validToken_setsContextDuringRequest_andClearsItAfter() throws Exception {
        String token = jwtService.generate(user()).token();
        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/files", "Bearer " + token), response,
                new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(jakarta.servlet.http.HttpServletRequest req,
                                           jakarta.servlet.http.HttpServletResponse res) {
                        seen.set(AuthContext.get());
                    }
                }));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(new AuthenticatedUser(5L, "alice@example.com"));
        assertThat(AuthContext.get()).isNull();   // cleared after the request
    }

    @Test
    void contextIsClearedEvenWhenControllerThrows() {
        String token = jwtService.generate(user()).token();

        assertThatThrownBy(() -> filter.doFilter(request("GET", "/api/files", "Bearer " + token),
                new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("boom");
                })).isInstanceOf(ServletException.class);

        assertThat(AuthContext.get()).isNull();
    }

    @Test
    void publicEndpoints_needNoToken() throws Exception {
        assertThat(run("POST", "/api/auth/register", null).getStatus()).isEqualTo(200);
        assertThat(run("POST", "/api/auth/login", null).getStatus()).isEqualTo(200);
        assertThat(run("GET", "/api/health", null).getStatus()).isEqualTo(200);
        assertThat(run("GET", "/api/shares/some-token", null).getStatus()).isEqualTo(200);
        assertThat(run("GET", "/api/shares/some-token/download", null).getStatus()).isEqualTo(200);
    }

    @Test
    void creatingAShare_stillRequiresLogin() throws Exception {
        assertThat(run("POST", "/api/files/1/shares", null).getStatus()).isEqualTo(401);
    }

    @Test
    void privateEndpoints_andOddUrls_areNotPublic() throws Exception {
        assertThat(run("GET", "/api/auth/me", null).getStatus()).isEqualTo(401);
        assertThat(run("GET", "/api/health/", null).getStatus()).isEqualTo(401);          // trailing slash
        assertThat(run("GET", "/api;x/files", null).getStatus()).isEqualTo(401);          // path-parameter trick
        assertThat(run("GET", "/api/shares/a/b", null).getStatus()).isEqualTo(401);
        assertThat(run("GET", "/api/shares/a/download/x", null).getStatus()).isEqualTo(401);
        assertThat(run("GET", "/api/shares/", null).getStatus()).isEqualTo(401);
        assertThat(run("GET", "/api/shares//download", null).getStatus()).isEqualTo(401);
        assertThat(run("POST", "/api/shares/some-token", null).getStatus()).isEqualTo(401); // only GET is public
        assertThat(run("GET", "/api/auth/login", null).getStatus()).isEqualTo(401);       // only POST is public
    }

    @Test
    void forgedClientHeaders_doNotGrantIdentity() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/files", null);
        request.addHeader("X-User-Id", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletResponse run(String method, String path, String authHeader) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path, authHeader), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String authHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        return request;
    }

    private static JwtService jwtService(Clock clock) {
        return new JwtService(new AppProperties(new AppProperties.Jwt(SECRET, 24),
                new AppProperties.Storage("unused", 1)), clock);
    }

    private static User user() {
        User u = new User();
        u.setId(5L);
        u.setEmail("alice@example.com");
        return u;
    }
}
