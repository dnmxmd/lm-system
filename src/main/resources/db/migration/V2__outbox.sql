-- Events are written here in the same transaction as the change that caused them, then delivered
-- by a relay. Before this table, anything handled after commit — notifications — was lost if the
-- process died in the gap, with nothing left to show it had been owed.
CREATE TABLE outbox_event (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(80)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    occurred_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP(6) WITH TIME ZONE,
    last_error      VARCHAR(1000),
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0)
);

-- The relay's hot query: the oldest pending events that are due.
CREATE INDEX idx_outbox_due ON outbox_event (status, next_attempt_at, occurred_at);

-- Retention sweeps by publication time.
CREATE INDEX idx_outbox_published_at ON outbox_event (published_at);
