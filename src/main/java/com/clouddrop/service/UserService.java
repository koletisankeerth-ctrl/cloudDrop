package com.clouddrop.service;

import com.clouddrop.entity.User;

public interface UserService {

    User register(String username, String email, String rawPassword);

    /** Returns the user if the credentials are correct, otherwise throws InvalidCredentialsException. */
    User authenticate(String email, String rawPassword);

    /** Loads the user for a validated token; throws UnauthorizedException if the account no longer exists. */
    User getById(Long id);
}
