package com.lms.user.dto;

import com.lms.common.security.Role;
import com.lms.user.model.User;
import com.lms.user.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** What callers are allowed to see about a user. The password hash never leaves the model. */
public record UserResponse(UUID id, String email, String fullName, Role role, UserStatus status,
                           Instant createdAt, Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getStatus(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
