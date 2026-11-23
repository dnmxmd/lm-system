package com.lms.outbox.service;

import java.util.UUID;

/**
 * An event this relay currently holds a lease on.
 *
 * <p>The claim id travels with it so that whatever finishes the work can prove it is still the
 * owner. Without that proof a relay which stalled past its lease would be free to overwrite the
 * result of whoever recovered the event in the meantime.
 */
public record ClaimedEvent(UUID id, UUID claimId, String type, String payload) {
}
