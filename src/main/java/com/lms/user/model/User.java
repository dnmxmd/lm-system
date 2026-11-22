package com.lms.user.model;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import com.lms.common.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A platform account.
 *
 * <p>State changes go through named methods that refuse illegal transitions, rather than open
 * setters: that way "suspend a user" has one meaning and one place to change it. The password is
 * only ever held as a hash — hashing happens in the service, which owns the encoder.
 */
@Entity
@Table(name = "users")
public class User {

    private static final int MAX_NAME_LENGTH = 150;
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected User() {
        // for JPA
    }

    private User(String email, String passwordHash, String fullName, Role role) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.email = normaliseEmail(email);
        this.passwordHash = requireHash(passwordHash);
        this.fullName = validName(fullName);
        this.role = role == null ? Role.STUDENT : role;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Creates a brand-new, active account. The password arrives already hashed. */
    public static User register(String email, String passwordHash, String fullName, Role role) {
        return new User(email, passwordHash, fullName, role);
    }

    /**
     * Addresses are stored lower-cased and trimmed, so "Ann@Example.com" and "ann@example.com"
     * can never become two accounts.
     */
    public static String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ValidationException("Email is required");
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (normalised.length() > 254 || !EMAIL_SHAPE.matcher(normalised).matches()) {
            throw new ValidationException("Email is not a valid address: " + email);
        }
        return normalised;
    }

    public void rename(String newFullName) {
        this.fullName = validName(newFullName);
        touch();
    }

    public void changeRole(Role newRole) {
        this.role = Objects.requireNonNull(newRole, "role is required");
        touch();
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = requireHash(newPasswordHash);
        touch();
    }

    public void suspend() {
        if (status == UserStatus.SUSPENDED) {
            throw new ConflictException("User " + id + " is already suspended");
        }
        this.status = UserStatus.SUSPENDED;
        touch();
    }

    public void activate() {
        if (status == UserStatus.ACTIVE) {
            throw new ConflictException("User " + id + " is already active");
        }
        this.status = UserStatus.ACTIVE;
        touch();
    }

    public boolean canSignIn() {
        return status == UserStatus.ACTIVE;
    }

    private static String requireHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new ValidationException("Password hash is required");
        }
        return passwordHash;
    }

    private static String validName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw new ValidationException("Full name is required");
        }
        String trimmed = fullName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("Full name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof User user && id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
