package com.lms.user;

import com.lms.common.error.ForbiddenException;
import com.lms.common.security.JwtService;
import com.lms.common.security.Role;
import com.lms.user.dto.AuthResponse;
import com.lms.user.dto.LoginRequest;
import com.lms.user.model.User;
import com.lms.user.repository.UserRepository;
import com.lms.user.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(users, passwordEncoder, jwtService);
    }

    private static User activeUser() {
        return User.register("ann@example.com", "$2a$hashed", "Ann Lee", Role.STUDENT);
    }

    @Test
    void returns_a_token_for_valid_credentials() {
        when(users.findByEmail("ann@example.com")).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("hunter2024", "$2a$hashed")).thenReturn(true);
        when(jwtService.issue(any(), any(), any()))
                .thenReturn(new JwtService.IssuedToken("token-value", Instant.now().plusSeconds(3600), 3600));

        AuthResponse response = authService.login(new LoginRequest("ann@example.com", "hunter2024"));

        assertThat(response.accessToken()).isEqualTo("token-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().email()).isEqualTo("ann@example.com");
    }

    @Test
    void an_unknown_address_fails_exactly_like_a_wrong_password() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "hunter2024")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void a_wrong_password_is_rejected_with_the_same_message() {
        when(users.findByEmail(any())).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ann@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void a_suspended_account_is_told_why_it_cannot_sign_in() {
        User suspended = activeUser();
        suspended.suspend();
        when(users.findByEmail(any())).thenReturn(Optional.of(suspended));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ann@example.com", "hunter2024")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("suspended");
    }
}
