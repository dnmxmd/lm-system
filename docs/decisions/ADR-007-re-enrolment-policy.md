# ADR-007: A successful, unrefunded purchase grants re-entry

**Status:** accepted

## Context

A learner can withdraw from a course and later rejoin. Until now rejoining always put them back in
`PENDING_PAYMENT`, whatever had happened before — including someone who had already paid in full.
Because a settled payment already existed, no new charge was raised either, so they were left
owing a payment that did not exist and could never be made. They were locked out of a course they
owned.

The question is what a prior payment entitles somebody to on the way back in.

## Decision

**A payment that succeeded and has not been refunded grants re-entry.** Rejoining is then free: no
second charge, no additional revenue, no payment-due notice, and access is active immediately.

By prior payment state:

| Prior payment | On rejoining | Why |
|---|---|---|
| **Succeeded**, not refunded | `ACTIVE` at once. No new charge, no revenue, no payment notice. | They bought the course. Withdrawing is not a refund. |
| **Pending** | `PENDING_PAYMENT`, the *same* charge reused. | They still owe what they always owed; it is not a new debt. |
| **Failed** | `PENDING_PAYMENT`, the charge put back in play. | A declined card is a reason to try again, not to be locked out. |
| **Refunded** | `PENDING_PAYMENT`, a **new** charge raised. | The money went back, so access has to be bought again. |
| **None** (free course) | `ACTIVE` at once. | Nothing was ever owed. |

Enrolment asks the question through `PaymentLedger` in `common`, for the same reason as
`CourseAccess`: payment already depends on enrolment, so declaring it in either would point the
two at each other.

### What this changed in the schema

`payments.enrollment_id` was unique, which made the refunded case impossible — there was nowhere
to put the replacement charge without destroying the refund. The constraint is gone (V3), and an
enrolment may now carry several payments over its life. "The payment" means the most recent one;
the rule that at most one is outstanding at a time is enforced in `PaymentService`.

## Consequences

- Somebody who paid can come and go freely, which is what buying a course ought to mean.
- Refund history survives a rejoin, so the ledger still answers what was charged and returned.
- Rejoining is counted as an enrolment in reporting, because it is one. Only **revenue** is left
  alone — that follows settled payments, and no payment settles on a free re-entry.
- A learner could withdraw and rejoin repeatedly, inflating the enrolment count for a course.
  Nothing here prevents that; if it ever matters, report distinct learners rather than enrolments.
