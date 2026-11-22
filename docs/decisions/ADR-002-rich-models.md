# ADR-002: Rules live on the model, not in the service

**Status:** accepted

## Context

The common Spring layout puts entities behind getters and setters and the rules in the service.
That works until the same rule is needed in two services, and then it is written twice and the
copies drift.

## Decision

Entities own their invariants. `Course.publish()` refuses a course with no lessons.
`Payment.succeed()` refuses a payment that is not pending. `Enrollment.completeLesson()` refuses to
record progress on an enrolment that has not been paid for. Constructors are private behind named
factories, and there are no setters.

Services orchestrate: they load, call the method, and own the transaction and the events.

## Consequences

- "What can a course do?" is answered by reading one file.
- An illegal transition is impossible rather than merely unlikely, whoever is calling.
- The rules are tested without Spring, a database or mocks — `CourseTest` runs in milliseconds.
- The entities are not anaemic, which means they are slightly less convenient to construct in
  tests and in mapping code. Named factories cover that.

The database still enforces what it should — unique addresses, one enrolment per learner per
course, one charge per enrolment — because an application-level check is a race, not a guarantee.
