package com.lms.user.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.Role;
import com.lms.user.dto.RegisterRequest;
import com.lms.user.dto.UserResponse;
import com.lms.user.event.UserRegistered;
import com.lms.user.model.User;
import com.lms.user.model.UserStatus;
import com.lms.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, ApplicationEventPublisher events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = User.normaliseEmail(request.email());

        // The unique index is the real guard against a race here; this check exists so the common
        // case gets a clear 409 rather than a constraint violation.
        if (users.existsByEmail(email)) {
            throw new ConflictException("An account already exists for " + email);
        }

        User user = users.save(User.register(email, passwordEncoder.encode(request.password()),
                request.fullName(), request.requestedRole()));

        events.publishEvent(new UserRegistered(user.getId(), user.getEmail(), user.getFullName(), user.getRole()));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse byId(UUID userId) {
        return UserResponse.from(require(userId));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Role role, PageQuery query) {
        var page = users.findByOptionalRole(role, query.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(page, UserResponse::from);
    }

    @Transactional
    public UserResponse changeStatus(UUID userId, UserStatus target) {
        User user = require(userId);
        if (target == UserStatus.SUSPENDED) {
            user.suspend();
        } else {
            user.activate();
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse rename(UUID userId, String fullName) {
        User user = require(userId);
        user.rename(fullName);
        return UserResponse.from(user);
    }

    /** The address to write to, or empty if there is no such account. */
    @Transactional(readOnly = true)
    public Optional<String> emailOf(UUID userId) {
        return users.findById(userId).map(User::getEmail);
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
    }
}
