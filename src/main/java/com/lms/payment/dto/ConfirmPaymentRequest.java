package com.lms.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmPaymentRequest(@NotBlank @Size(max = 100) String providerReference) {
}
