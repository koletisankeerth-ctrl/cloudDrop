package com.clouddrop.auth;

import com.clouddrop.exception.UnauthorizedException;

/**
 * Holds the authenticated user for the CURRENT request.
 * A servlet container handles one request per thread at a time, so a ThreadLocal gives each
 * request its own private slot. JwtAuthFilter sets it and MUST clear it in a finally block:
 * Tomcat reuses threads, so a forgotten value would leak one user's identity into the next request.
 */
public final class AuthContext {

    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthenticatedUser user) {
        CURRENT.set(user);
    }

    public static AuthenticatedUser get() {
        return CURRENT.get();
    }

    /** For code that needs a logged-in user: throws 401 if there is none. */
    public static AuthenticatedUser requireUser() {
        AuthenticatedUser user = CURRENT.get();
        if (user == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
