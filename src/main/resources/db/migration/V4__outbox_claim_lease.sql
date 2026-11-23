-- A claim had no owner and no expiry measured from when it was taken, so a relay that stalled
-- could come back and overwrite the result of whoever had since recovered the event. Each claim
-- now has an identity and a lease, and every update that ends a claim names the claim it is ending.
ALTER TABLE outbox_event ADD COLUMN claim_id UUID;
ALTER TABLE outbox_event ADD COLUMN claim_expires_at TIMESTAMP(6) WITH TIME ZONE;

-- The relay's recovery query: claimed leases that have run out.
CREATE INDEX idx_outbox_claim_expiry ON outbox_event (status, claim_expires_at);
