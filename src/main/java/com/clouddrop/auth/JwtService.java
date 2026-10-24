package com.clouddrop.auth;

import com.clouddrop.config.AppProperties;
import com.clouddrop.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Creates and validates JWTs (HS256, signed with the secret from JWT_SECRET).
 * A token carries: iss, sub (user id), email, iat, exp. Validation checks the signature, the
 * algorithm, the issuer, the expiry, and that sub and email are present and well formed.
 */
@Service
public class JwtService {

    private static final String ISSUER = "clouddrop";
    private static final String EMAIL_CLAIM = "email";

    private final SecretKey key;
    private final Duration lifetime;
    private final Clock clock;   // injected so tests can control "now"

    public JwtService(AppProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.lifetime = Duration.ofHours(props.jwt().expirationHours());
        this.clock = clock;
    }

    public IssuedToken generate(com.clouddrop.entity.User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim(EMAIL_CLAIM, user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Returns the identity in the token, or throws InvalidTokenException for ANY problem. */
    public AuthenticatedUser parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)                                  // checks the signature
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))          // exp is checked against this clock
                    .build()
                    .parseSignedClaims(token);                        // rejects unsigned tokens ("alg: none")

            if (!"HS256".equals(jws.getHeader().getAlgorithm())) {
                throw new InvalidTokenException("Unexpected signing algorithm");
            }
            Claims claims = jws.getPayload();
            if (claims.getExpiration() == null) {                     // jjwt does not require exp, we do
                throw new InvalidTokenException("Missing exp claim");
            }
            String email = claims.get(EMAIL_CLAIM, String.class);
            if (claims.getSubject() == null || email == null || email.isBlank()) {
                throw new InvalidTokenException("Missing required claims");
            }
            return new AuthenticatedUser(Long.parseLong(claims.getSubject()), email);
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException: bad signature, expired, malformed, ...  IllegalArgumentException: blank token, non-numeric sub
            if (e instanceof InvalidTokenException ite) {
                throw ite;
            }
            throw new InvalidTokenException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
