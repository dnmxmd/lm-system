package com.lms.user;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import com.lms.common.security.Role;
import com.lms.user.model.User;
import com.lms.user.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static User anyUser() {
        return User.register("ann@example.com", "hashed", "Ann Lee", Role.STUDENT);
    }

    @Test
    void registers_an_active_student_by_default() {
        User user = User.register("ann@example.com", "hashed", "Ann Lee", null);

        assertThat(user.getRole()).isEqualTo(Role.STUDENT);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.canSignIn()).isTrue();
        assertThat(user.getId()).isNotNull();
    }

    @Test
    void normalises_the_email_so_case_cannot_create_a_second_account() {
        assertThat(User.normaliseEmail("  Ann@Example.COM ")).isEqualTo("ann@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-at-sign", "two@@at.com", "missing@domain"})
    void rejects_addresses_that_are_not_addresses(String candidate) {
        assertThatThrownBy(() -> User.normaliseEmail(candidate)).isInstanceOf(ValidationException.class);
    }

    @Test
    void refuses_to_register_without_a_name() {
        assertThatThrownBy(() -> User.register("ann@example.com", "hashed", "  ", Role.STUDENT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Full name");
    }

    @Test
    void refuses_to_register_without_a_password_hash() {
        assertThatThrownBy(() -> User.register("ann@example.com", " ", "Ann Lee", Role.STUDENT))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void a_suspended_user_cannot_sign_in() {
        User user = anyUser();
        user.suspend();

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.canSignIn()).isFalse();
    }

    @Test
    void suspending_twice_is_a_conflict_rather_than_a_silent_no_op() {
        User user = anyUser();
        user.suspend();

        assertThatThrownBy(user::suspend).isInstanceOf(ConflictException.class);
    }

    @Test
    void reactivating_restores_sign_in() {
        User user = anyUser();
        user.suspend();
        user.activate();

        assertThat(user.canSignIn()).isTrue();
    }
}
