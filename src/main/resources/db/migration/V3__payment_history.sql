-- A learner who is refunded and later rejoins needs a second charge, and the refunded one has to
-- stay on the record. One payment per enrolment made that impossible, so the uniqueness moves from
-- the row to the rule: at most one payment is outstanding at a time, enforced in PaymentService.
ALTER TABLE payments DROP CONSTRAINT uq_payments_enrollment;

CREATE INDEX idx_payments_enrollment ON payments (enrollment_id, created_at DESC);
