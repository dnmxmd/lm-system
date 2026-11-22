package com.lms.user.event;

import com.lms.common.security.Role;

import java.util.UUID;

/**
 * Published after a successful registration. Listeners run after the transaction commits, so
 * nothing reacts to an account that was rolled back.
 */
public record UserRegistered(UUID userId, String email, String fullName, Role role) {
}
