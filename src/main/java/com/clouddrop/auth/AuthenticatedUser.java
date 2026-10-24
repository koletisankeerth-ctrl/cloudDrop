package com.clouddrop.auth;

/** The identity of the caller, built ONLY from a validated JWT. */
public record AuthenticatedUser(Long id, String email) {
}
