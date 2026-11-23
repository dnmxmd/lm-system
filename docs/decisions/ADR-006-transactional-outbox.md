# ADR-006: A transactional outbox for anything handled after the commit

**Status:** accepted

## Context

ADR-003 left a hole and named it: notifications ran in an after-commit listener, so an event was
lost if the process died between the commit and the listener running. Nothing was left behind to
show that a welcome email had ever been owed. For mail that was tolerable; it stops being
tolerable the moment anything else needs delivering.

The usual answer is a message broker. This is a single deployable with one database, and ADR-001
is about not paying for distribution there is no need for.

## Decision

Events that have an effect outside this process are written to an `outbox_event` row **in the same
transaction as the change that caused them**, and delivered afterwards by a relay.

- `OutboxRecorder` listens with a plain `@EventListener`, so the row is part of the same commit.
  `OutboxStore.record` is `@Transactional(MANDATORY)`: recording outside a transaction is a
  programming error, not a fallback, because a row written in its own transaction is the very gap
  this exists to close.
- `OutboxRelay` claims rows, hands them to `OutboxConsumer`s, and marks them delivered. It holds no
  transaction while a consumer runs, so mail is never sent with a database connection in hand.
- Claiming is a conditional `UPDATE ... WHERE status = PENDING`. It behaves the same on PostgreSQL
  and H2, and two instances racing resolve by one of them getting zero rows back.
- Failures back off, doubling to a cap of an hour, and dead-letter after eight attempts so somebody
  is told rather than the event being retried forever.
- The relay runs on a timer **and** is nudged after each commit. The timer is what makes delivery
  reliable; the nudge is what stops a welcome email waiting for the next tick. A nudge alone can
  die with the process; a timer alone feels broken.

**Only events with an out-of-process consequence go through it.** Analytics counting and payment
creation stay as in-transaction listeners. Routing them through the outbox would make counting
eventually consistent and undo the atomicity between enrolling and raising a charge — paying a
real cost to fix a problem they do not have.

## Consequences

- Nothing handled after a commit is lost to a crash.
- The outbox is also the audit trail: `GET /api/v1/events` answers "what did the system actually
  see", and dead letters can be requeued once whatever broke is fixed.
- Delivery is **at least once**. A send that succeeded and then failed to be marked will go out
  twice. For mail that is the right way round — a duplicate welcome message is a nuisance, a
  missing one is a bug. Anything less forgiving needs its consumer to be idempotent.
- There is a table to keep an eye on. Retention purges delivered rows after thirty days; a growing
  `FAILED` count is the thing worth alerting on.
- `lms.outbox.auto-delivery: false` pauses the relay, which is how tests drive it a pass at a time
  and how an operator stops delivery without stopping the application.
