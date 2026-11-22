package com.lms.user.service;

import com.lms.common.error.ForbiddenException;
import com.lms.common.security.JwtService;
import com.lms.user.dto.AuthResponse;
import com.lms.user.dto.LoginRequest;
import com.lms.user.dto.UserResponse;
import com.lms.user.model.User;
import com.lms.user.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // The same error whether the address is unknown or the password is wrong, so the response
        // cannot be used to discover which addresses have accounts.
        User user = users.findByEmail(User.normaliseEmail(request.email()))
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!user.canSignIn()) {
            throw new ForbiddenException("This account is suspended");
        }

        var token = jwtService.issue(user.getId(), user.getEmail(), user.getRole());
        return AuthResponse.bearer(token.accessToken(), token.expiresAt(), token.expiresInSeconds(),
                UserResponse.from(user));
    }
}
