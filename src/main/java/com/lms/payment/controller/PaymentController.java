package com.lms.payment.controller;

import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.payment.dto.ConfirmPaymentRequest;
import com.lms.payment.dto.FailPaymentRequest;
import com.lms.payment.dto.PaymentResponse;
import com.lms.payment.service.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Payments")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "The signed-in learner's payments")
    @GetMapping("/me")
    public PageResponse<PaymentResponse> mine(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.forStudent(caller.id(), PageQuery.of(page, size));
    }

    @Operation(summary = "Read one payment")
    @GetMapping("/{paymentId}")
    public PaymentResponse byId(@PathVariable UUID paymentId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.byId(paymentId, caller);
    }

    @Operation(summary = "What is owed on an enrolment")
    @GetMapping("/by-enrollment/{enrollmentId}")
    public PaymentResponse byEnrollment(@PathVariable UUID enrollmentId,
                                        @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.forEnrollment(enrollmentId, caller);
    }

    @Operation(summary = "Refund a settled payment")
    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentResponse refund(@PathVariable UUID paymentId) {
        return paymentService.refund(paymentId);
    }
}
