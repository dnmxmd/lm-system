package com.lms.payment.controller;

import com.lms.common.security.AuthenticatedUser;
import com.lms.payment.dto.ConfirmPaymentRequest;
import com.lms.payment.dto.FailPaymentRequest;
import com.lms.payment.dto.PaymentResponse;
import com.lms.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Stands in for a payment provider while there isn't one.
 *
 * <p>These endpoints let the signed-in learner settle their own charge, which is exactly what a
 * real deployment must not allow. They exist only when
 * {@code lms.payments.allow-simulated-settlement} is explicitly switched on, and
 * {@link com.lms.config.DeploymentSafetyCheck} refuses to start if that happens outside
 * development.
 *
 * <p>Replacing this with a real provider means a webhook that verifies the provider's signature
 * and carries no caller identity at all.
 */
@Tag(name = "Payments (simulated provider)")
@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnProperty(prefix = "lms.payments", name = "allow-simulated-settlement", havingValue = "true")
public class SimulatedSettlementController {

    private final PaymentService paymentService;

    public SimulatedSettlementController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Settle a pending payment (development only)")
    @PostMapping("/{paymentId}/confirm")
    public PaymentResponse confirm(@PathVariable UUID paymentId,
                                   @Valid @RequestBody ConfirmPaymentRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.confirm(paymentId, request.providerReference(), caller);
    }

    @Operation(summary = "Mark a pending payment as declined (development only)")
    @PostMapping("/{paymentId}/fail")
    public PaymentResponse fail(@PathVariable UUID paymentId,
                                @Valid @RequestBody FailPaymentRequest request,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.fail(paymentId, request.reason(), caller);
    }

    @Operation(summary = "Put a declined payment back in play (development only)")
    @PostMapping("/{paymentId}/retry")
    public PaymentResponse retry(@PathVariable UUID paymentId,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return paymentService.retry(paymentId, caller);
    }
}
