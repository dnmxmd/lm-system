package com.lms.user.repository;

import com.lms.common.security.Role;
import com.lms.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** {@code role} is optional; null returns every role. */
    @Query("select u from User u where (:role is null or u.role = :role)")
    Page<User> findByOptionalRole(@Param("role") Role role, Pageable pageable);
}
