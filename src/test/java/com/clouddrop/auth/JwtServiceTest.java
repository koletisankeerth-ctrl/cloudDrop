package com.clouddrop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clouddrop.config.AppProperties;
import com.clouddrop.entity.User;
import com.clouddrop.exception.InvalidTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = "unit-test-secret-that-is-at-least-32-chars-long";
    static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    JwtService service = serviceWith(SECRET, Clock.systemUTC());

    @Test
    void generatedToken_roundTrips() {
        IssuedToken issued = service.generate(user(42L, "alice@example.com"));

        AuthenticatedUser parsed = service.parse(issued.token());

        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("alice@example.com");
        assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
    }

    @Test
    void expiredToken_isRejected() {
        Clock twoDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
        String oldToken = serviceWith(SECRET, twoDaysAgo).generate(user(1L, "a@b.com")).token();

        assertThatThrownBy(() -> service.parse(oldToken)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenSignedWithOtherSecret_isRejected() {
        String forged = serviceWith("a-completely-different-secret-of-32-plus-chars", Clock.systemUTC())
                .generate(user(1L, "a@b.com")).token();

        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void garbageAndBlankTokens_areRejected() {
        assertThatThrownBy(() -> service.parse("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parse("")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unsignedToken_isRejected() {
        String unsigned = Jwts.builder().issuer("clouddrop").subject("1").claim("email", "a@b.com")
                .expiration(Date.from(Instant.now().plusSeconds(600))).compact();

        assertThatThrownBy(() -> service.parse(unsigned)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenWithoutExpiry_isRejected() {
        String token = signed(Jwts.builder().issuer("clouddrop").subject("1").claim("email", "a@b.com"));

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenWithoutEmailClaim_isRejected() {
        String token = signed(Jwts.builder().issuer("clouddrop").subject("1")
                .expiration(Date.from(Instant.now().plusSeconds(600))));

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenWithNonNumericSubject_isRejected() {
        String token = signed(Jwts.builder().issuer("clouddrop").subject("abc").claim("email", "a@b.com")
                .expiration(Date.from(Instant.now().plusSeconds(600))));

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenWithWrongIssuer_isRejected() {
        String token = signed(Jwts.builder().issuer("someone-else").subject("1").claim("email", "a@b.com")
                .expiration(Date.from(Instant.now().plusSeconds(600))));

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    private static String signed(io.jsonwebtoken.JwtBuilder builder) {
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private static JwtService serviceWith(String secret, Clock clock) {
        return new JwtService(new AppProperties(new AppProperties.Jwt(secret, 24),
                new AppProperties.Storage("unused", 1)), clock);
    }

    private static User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }
}
