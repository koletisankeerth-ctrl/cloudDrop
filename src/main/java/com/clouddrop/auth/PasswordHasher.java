package com.clouddrop.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the standalone BCrypt library (at.favre.lib), so the rest of the
 * app never touches the library directly. BCrypt salts each hash automatically and the
 * salt is stored inside the hash string.
 */
@Component
public class PasswordHasher {

    private static final int COST = 12;          // 2^12 rounds; raise over time as hardware improves
    public static final int MAX_PASSWORD_BYTES = 72; // BCrypt silently ignores/rejects anything beyond 72 bytes

    public String hash(String rawPassword) {
        if (tooLong(rawPassword)) {
            throw new IllegalArgumentException("Password exceeds " + MAX_PASSWORD_BYTES + " bytes");
        }
        return BCrypt.withDefaults().hashToString(COST, rawPassword.toCharArray());
    }

    public boolean matches(String rawPassword, String hash) {
        if (tooLong(rawPassword)) {
            return false;
        }
        return BCrypt.verifyer().verify(rawPassword.toCharArray(), hash).verified;
    }

    public static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
