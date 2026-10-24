package com.clouddrop.dto;

import com.clouddrop.entity.User;
import java.time.Instant;

// Public view of a user. Deliberately has no passwordHash.
public record UserResponse(Long id, String username, String email, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt());
    }
}
