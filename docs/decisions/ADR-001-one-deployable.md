# ADR-001: One deployable, not a set of services

**Status:** accepted

## Context

The original sketch for this project was a microservices monorepo: a gateway, five services, three
workers, a database each, and Kafka between them.

The work that an LMS actually does — publish a course, enrol somebody, take a payment, show
progress — is a handful of tables and a few rules over them. Splitting it across services would mean
paying for distribution before there is anything to distribute: a saga in place of a transaction,
a deduplication ledger in place of a unique index, and six things to start before anything works.

## Decision

One Spring Boot application, one PostgreSQL database, one Dockerfile.

Features keep their own packages and talk to each other through Spring application events rather
than direct calls, so the seams a split would follow are already drawn and already named.

## Consequences

Good:

- Enrolment and payment settle in a single transaction. There is no window in which money has been
  taken and the learner is locked out, and no compensating action to write for when there is.
- Events are delivered exactly once within a transaction, so the counting in `analytics` needs no
  deduplication table.
- One process to run, one log to read, one thing to deploy.

Bad:

- Everything scales together. If reporting ever becomes expensive, it will take the API with it.
- A slow query anywhere uses a connection everywhere.
- Nothing enforces the boundaries at runtime — only `ArchitectureTest` does, at build time.

## When to revisit

When one feature's load genuinely differs from the rest, or when separate teams own separate
features and the single deployable becomes a release bottleneck. Until then, the events are the
seam and `ArchitectureTest` keeps it clean.
