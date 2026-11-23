# ADR-004: The catalog is public, the content is not

**Status:** accepted

## Context

Course detail was one response served to everybody, and `GET /api/v1/courses/{id}` is deliberately
open so that people can browse before signing up. That response embedded every lesson, each with
its `contentUrl`.

The effect was that the thing learners pay for was available to anyone who could guess or read a
course id — no account, no enrolment, no payment. The catalog was giving away the product in order
to advertise it.

## Decision

Two projections of the same course, chosen by who is asking.

- **Syllabus** — title, summary, price, level, and each lesson's title, position and length.
  Public, and the only version that is cached.
- **With content** — the same plus `contentUrl`. Returned to the instructor who owns the course,
  to an admin, and to a learner whose enrolment is `ACTIVE` or `COMPLETED`.

`CourseResponse.syllabus(...)` and `CourseResponse.withContent(...)` are separate factory methods
rather than one method taking a boolean, so every call site has to state which audience it is
answering. Both detail routes — by id and by slug — go through the same check.

Entitlement is asked through `CourseAccess`, an interface in `common`. The catalog has to ask the
question and only enrolment can answer it, so putting the interface in either feature would point
the two at each other; `ArchitectureTest` would reject that, correctly.

## Consequences

- A pending, unpaid enrolment does not unlock content. That is what paying is for.
- Only the public projection is cached. Caching the entitled one under a course-only key would
  serve one learner's access to the next caller, which is a worse bug than the one being fixed.
- An entitled read is not cached at all, so it costs a query. Instructors and enrolled learners are
  a small fraction of catalog traffic, so this is the cheap side of the trade.
- Anything new added to `CourseResponse` has to be classified as public or not. That is a feature:
  the decision becomes visible in the diff.
