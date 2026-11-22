package com.lms.payment.dto;

import jakarta.validation.constraints.Size;

public record FailPaymentRequest(@Size(max = 500) String reason) {
}
