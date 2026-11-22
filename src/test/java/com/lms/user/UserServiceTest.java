package com.lms.user;

import com.lms.common.error.ConflictException;
import com.lms.common.security.Role;
import com.lms.user.dto.RegisterRequest;
import com.lms.user.dto.UserResponse;
import com.lms.user.event.UserRegistered;
import com.lms.user.model.User;
import com.lms.user.repository.UserRepository;
import com.lms.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher events;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(users, passwordEncoder, events);
    }

    @Test
    void stores_the_hash_and_never_the_raw_password() {
        when(passwordEncoder.encode("hunter2024")).thenReturn("$2a$hashed");
        when(users.existsByEmail("ann@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.register(
                new RegisterRequest("ann@example.com", "hunter2024", "Ann Lee", Role.INSTRUCTOR));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$hashed");
        assertThat(response.email()).isEqualTo("ann@example.com");
        assertThat(response.role()).isEqualTo(Role.INSTRUCTOR);
    }

    @Test
    void registration_cannot_grant_itself_admin() {
        when(passwordEncoder.encode(any())).thenReturn("$2a$hashed");
        when(users.existsByEmail(any())).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.register(
                new RegisterRequest("ann@example.com", "hunter2024", "Ann Lee", Role.ADMIN));

        assertThat(response.role()).isEqualTo(Role.STUDENT);
    }

    @Test
    void announces_the_new_account() {
        when(passwordEncoder.encode(any())).thenReturn("$2a$hashed");
        when(users.existsByEmail(any())).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.register(new RegisterRequest("ann@example.com", "hunter2024", "Ann Lee", Role.STUDENT));

        ArgumentCaptor<UserRegistered> event = ArgumentCaptor.forClass(UserRegistered.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().email()).isEqualTo("ann@example.com");
        assertThat(event.getValue().role()).isEqualTo(Role.STUDENT);
    }

    @Test
    void refuses_a_duplicate_address_without_touching_storage() {
        when(users.existsByEmail("ann@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(
                new RegisterRequest("ann@example.com", "hunter2024", "Ann Lee", Role.STUDENT)))
                .isInstanceOf(ConflictException.class);

        verify(users, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
