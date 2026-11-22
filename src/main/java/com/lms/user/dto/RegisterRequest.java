package com.lms.user.dto;

import com.lms.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters") String password,
        @NotBlank @Size(max = 150) String fullName,
        Role role) {

    /** Anyone may sign up as a student or instructor; granting ADMIN is an operator action. */
    public Role requestedRole() {
        return role == null || role == Role.ADMIN ? Role.STUDENT : role;
    }
}
