package com.lms.user.controller;

import com.lms.common.error.ForbiddenException;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.common.security.Role;
import com.lms.user.dto.UpdateProfileRequest;
import com.lms.user.dto.UpdateStatusRequest;
import com.lms.user.dto.UserResponse;
import com.lms.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "The caller's own profile")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return userService.byId(caller.id());
    }

    @Operation(summary = "Rename the caller's own profile")
    @PutMapping("/me")
    public UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return userService.rename(caller.id(), request.fullName());
    }

    @Operation(summary = "Read a user; your own record, or anyone's if you are an admin")
    @GetMapping("/{userId}")
    public UserResponse byId(@PathVariable UUID userId, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!caller.isAdmin() && !caller.is(userId)) {
            throw new ForbiddenException("You may only read your own profile");
        }
        return userService.byId(userId);
    }

    @Operation(summary = "List users, optionally filtered by role")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> list(@RequestParam(required = false) Role role,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return userService.list(role, PageQuery.of(page, size));
    }

    @Operation(summary = "Suspend or reactivate an account")
    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse updateStatus(@PathVariable UUID userId, @Valid @RequestBody UpdateStatusRequest request) {
        return userService.changeStatus(userId, request.status());
    }
}
