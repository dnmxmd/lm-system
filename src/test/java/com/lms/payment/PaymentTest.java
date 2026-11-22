package com.lms.payment;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import com.lms.common.money.Money;
import com.lms.payment.model.Payment;
import com.lms.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static Payment pending() {
        return Payment.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.of(new BigDecimal("49.99"), "USD"));
    }

    @Test
    void a_new_payment_is_pending_and_unsettled() {
        Payment payment = pending();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.isSettled()).isFalse();
        assertThat(payment.getSettledAt()).isNull();
    }

    @Test
    void a_free_enrollment_does_not_get_a_payment() {
        assertThatThrownBy(() -> Payment.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.zero("USD")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void confirming_settles_the_payment_and_keeps_the_provider_reference() {
        Payment payment = pending();

        payment.succeed("ch_1234567890");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderReference()).isEqualTo("ch_1234567890");
        assertThat(payment.getSettledAt()).isNotNull();
    }

    @Test
    void a_second_callback_cannot_charge_the_same_payment_twice() {
        Payment payment = pending();
        payment.succeed("ch_1234567890");

        assertThatThrownBy(() -> payment.succeed("ch_duplicate")).isInstanceOf(ConflictException.class);
        assertThat(payment.getProviderReference()).isEqualTo("ch_1234567890");
    }

    @Test
    void confirming_without_a_provider_reference_is_refused() {
        assertThatThrownBy(() -> pending().succeed("  ")).isInstanceOf(ValidationException.class);
    }

    @Test
    void a_declined_payment_records_a_reason_and_cannot_then_succeed() {
        Payment payment = pending();

        payment.fail("Card declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Card declined");
        assertThatThrownBy(() -> payment.succeed("ch_late")).isInstanceOf(ConflictException.class);
    }

    @Test
    void a_decline_without_a_reason_still_records_one() {
        Payment payment = pending();

        payment.fail(null);

        assertThat(payment.getFailureReason()).isEqualTo("Declined by the provider");
    }

    @Test
    void only_a_settled_payment_can_be_refunded() {
        Payment payment = pending();

        assertThatThrownBy(payment::refund).isInstanceOf(ConflictException.class);

        payment.succeed("ch_1234567890");
        payment.refund();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refunding_twice_is_refused() {
        Payment payment = pending();
        payment.succeed("ch_1234567890");
        payment.refund();

        assertThatThrownBy(payment::refund).isInstanceOf(ConflictException.class);
    }
}
