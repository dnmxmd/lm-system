# ADR-003: In-process events, with the transaction phase chosen per listener

**Status:** accepted (revised after load testing)

## Context

Features need to react to each other: a paid enrolment needs a charge raising, reporting needs
counting, learners need telling. Direct calls between features would tangle them and make the
dependency graph a cycle waiting to happen.

## Decision

Features publish records through `ApplicationEventPublisher`. Listeners pick their phase
deliberately:

| Listener | Phase | Why |
|---|---|---|
| `AnalyticsService` | `@EventListener` — inside the publisher's transaction | A rolled-back enrolment must not leave a count behind. Counters move by atomic `UPDATE ... SET x = x + 1`, so this adds no contention to the request. |
| `PaymentService.onEnrollmentCreated` | `@EventListener` — inside the publisher's transaction | Enrolling and raising the charge are all or nothing, and share one database connection. |
| `NotificationListener` | `@TransactionalEventListener` + `@Async` — after commit, off the request thread | Nobody should be emailed about something that rolled back, and a slow mail server must hold up neither a transaction nor the learner's request. |

## What load testing changed

The first version of this decision had `PaymentService.onEnrollmentCreated` run **after commit in
a new transaction**, reasoning that a failure to raise the charge should not undo the enrolment.

`ConcurrentEnrolmentTest` showed what that actually costs. An after-commit listener that opens
`REQUIRES_NEW` needs a second connection *while the first is still held*, so every enrolment
needed two at once. Twelve learners enrolling in the same course drained the pool and two of them
got a 500 after waiting thirty seconds for a connection that was never coming.

Running it in the publisher's transaction uses one connection and makes the pair atomic. The
scenario the original design protected against — an enrolment left pending with no charge raised —
turns out to be the worse of the two states anyway: nothing in the system would ever resolve it.

A second lesson from the same test: the reporting counters used to be read-modify-write on a
`@Version`ed row, which meant concurrent learners on one course knocked each other out. Ten of
twelve enrolments failed with a `409` caused entirely by bookkeeping. They are atomic statements
now.

## Consequences

- Counting is exact without a deduplication ledger, because it shares the publisher's transaction.
- Enrolment and its charge commit or fail together.
- Mail failures are logged, not propagated: a notification is not worth undoing an enrolment for.
- The async notification pool draws on the same connection pool as the request threads, so
  `DB_POOL_SIZE` has to exceed both. It defaults to 20 against an async pool of 8.
- Events are lost if the process dies between commit and the async listener running. For mail that
  is acceptable. If it ever is not, the fix is an outbox table — not a queue.
