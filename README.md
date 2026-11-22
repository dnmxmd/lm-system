# Learning Management System

A Spring Boot application for running courses: instructors write and publish them, learners enrol,
pay if they need to, and work through the lessons. One deployable, one database, one Dockerfile.

Java 21 · Spring Boot 3.5 · PostgreSQL · Redis · Flyway

## Run it

```bash
cp .env.example .env
docker compose up --build
```

That starts PostgreSQL, Redis and the application together. Then:

- API docs: <http://localhost:8080/swagger-ui.html>
- Health: <http://localhost:8080/actuator/health>
- Metrics: <http://localhost:8080/actuator/prometheus>

To run the application from your IDE instead, start just the dependencies:

```bash
docker compose up -d postgres redis
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile has to be named. Without it the application refuses to start on the example
signing key, and refuses to expose the stand-in payment endpoints — which is the point: the
configuration most likely to reach a server by accident is the one that gets no exemptions.

Tests need neither — they run on an in-memory database:

```bash
mvn test
```

## How the code is laid out

```
src/main/java/com/lms/
├── LmsApplication.java
├── config/          security, caching, OpenAPI
├── common/          errors, paging, money, roles, JWT, the API error shape
├── gateway/         request correlation and rate limiting
├── user/            accounts, roles, sign-in
├── course/          catalog, lessons, publishing
├── enrollment/      who is in which course, and how far they have got
├── payment/         charges for paid enrolments
├── analytics/       reporting totals, built from what the others announce
└── notification/    the mail people receive
```

Each feature owns the same five folders:

```
user/
├── controller/   HTTP in, DTOs out. No business rules.
├── service/      the rules, and the transaction boundary
├── repository/   Spring Data; the only place that talks to the database
├── model/        the entities, with the behaviour that protects their invariants
├── dto/          what crosses the HTTP boundary
└── event/        what this feature announces when something happens
```

The point of the split is that `course` can be read, changed or tested without reading `payment`.
`ArchitectureTest` fails the build if a feature reaches into another's repository, if a controller
is called from anywhere but HTTP, or if two features start depending on each other in a circle.

### Why features talk through events

`enrollment` does not call `payment`, and neither of them calls `analytics`. They publish a record
of what happened and whoever cares listens:

```
enrol in a paid course
        └── EnrollmentCreated ──┬── payment      raises the charge
                                ├── analytics    counts the enrolment
                                └── notification "we are waiting for your payment"

confirm the payment
        └── PaymentSucceeded ───┬── analytics    records the revenue
                                └── notification "you are in"
```

Listeners that change data run inside the publisher's transaction, so a rolled-back enrolment never
leaves a count behind and enrolling never leaves an unraised charge. Listeners that talk to the
outside world — mail — run **after** the commit and on a separate thread, so nobody is told about
something that did not happen and a slow mail server holds up neither a transaction nor the
learner's request.

The reporting counters move with atomic `UPDATE ... SET x = x + 1` rather than by loading a row and
saving it back. That matters more than it looks: read-then-write made twelve learners enrolling in
one course knock each other out, and `ConcurrentEnrolmentTest` exists to keep it fixed.

This is the seam to cut along if the application is ever split into services: the events already
exist, and only their delivery would change.

## The domain, briefly

**Courses** go `DRAFT → PUBLISHED → ARCHIVED`. A course cannot be published without a summary and
at least one lesson. Lesson positions are always `1..n` with no gaps — removing one renumbers the
rest. A published course keeps its slug when renamed, so public links never break. Only the
instructor who created a course, or an admin, can change it.

**Enrolments** hold progress as the *set of finished lesson ids*, not a counter. Finishing the same
lesson twice changes nothing, lessons can be done in any order, and if the instructor adds a lesson
afterwards a completed enrolment reopens instead of staying falsely complete.

**Payments** are a state machine: `PENDING → SUCCEEDED → REFUNDED`, or `PENDING → FAILED`. Every
other transition is a `409`. That is what makes a duplicated provider callback harmless. Confirming
a payment and opening the course happen in one transaction, so there is no window where the money
is taken but the learner is locked out.

## API

Everything is under `/api/v1`. Full detail, with request and response shapes, is in Swagger.

| | |
|---|---|
| `POST /auth/register`, `POST /auth/login` | open to anyone |
| `GET /courses`, `GET /courses/{id}`, `GET /courses/by-slug/{slug}` | open to anyone |
| `POST /courses`, `PUT /courses/{id}`, `/publish`, `/archive`, `/lessons` | instructor, admin |
| `POST /enrollments`, `GET /enrollments/me`, `/complete`, `/cancel` | the learner |
| `GET /enrollments/by-course/{id}` | instructor, admin |
| `GET /payments/me`, `/by-enrollment/{id}`, `POST /{id}/confirm`, `/fail` | the learner |
| `POST /payments/{id}/refund` | admin |
| `GET /analytics/courses`, `/courses/{id}` | instructor, admin |
| `GET /analytics/overview`, `GET /users`, `PATCH /users/{id}/status` | admin |

Errors all share one shape, so clients write one parser. `code` is stable; `message` is for people.

```json
{
  "timestamp": "2026-11-22T10:15:30Z",
  "status": 409,
  "code": "CONFLICT",
  "message": "You are already enrolled in course 7f3e...",
  "path": "/api/v1/enrollments"
}
```

## Security

Stateless JWT. `user-service` signs a token at login; the filter in `common/security` verifies it
on every request and puts an `AuthenticatedUser` in the security context. Roles are `STUDENT`,
`INSTRUCTOR`, `ADMIN`; route rules live in `SecurityConfig`, and anything finer is `@PreAuthorize`
on the method.

Registration will not grant `ADMIN` however the request asks for it — promote an account from the
database or an operator tool.

### What the application refuses to do

Outside a development profile (`dev`, `local`, `test`, `schema`, `realinfra`) it will not start if:

- `JWT_SECRET` is still the example value from this repository, or
- `lms.payments.allow-simulated-settlement` is true.

Both are conveniences that would be holes in a real deployment — a signing key anyone can read
mints admin tokens, and simulated settlement lets a learner grant themselves a paid course. A
warning in a log nobody reads is not enough, so startup fails instead.

### Who can see lesson content

The catalog is public so people can decide whether to enrol: titles, lengths, prices, summaries.
`contentUrl` is **not** part of that. It is returned only to the instructor who owns the course,
to an admin, or to a learner whose enrolment is `ACTIVE` or `COMPLETED` — so a pending, unpaid
enrolment does not unlock it. The cached copy is the public one, so a cache hit can never hand out
someone else's access.

## Testing

```
mvn test     # 97 tests, no Docker needed
```

- **Model tests** cover the rules — what a course refuses to do, what a payment refuses to become.
- **Service tests** use mocks to check orchestration: what gets saved, what gets announced, what
  is refused before anything is written.
- **`SchemaMigrationTest`** runs the real migration and makes Hibernate validate the entity
  mappings against it, so the two cannot drift apart unnoticed.
- **`LearnerJourneyTest`** drives the whole thing over HTTP — sign up, author a course, publish,
  enrol, pay, finish the lessons, read the report — and checks the authorisation rules hold.
- **`ConcurrentEnrolmentTest`** puts a dozen learners into one course at the same instant and
  checks they all get in and all get counted. This is the test that caught the counter contention
  and the connection-pool starvation described in ADR-003.
- **`LessonRemovedDuringCourseTest`** covers an instructor editing a course people are part way
  through — a learner must not keep credit for a lesson that is no longer in it.
- **`RealInfrastructureTest`** runs the journey against the PostgreSQL and Redis from
  `docker-compose`, including two consecutive cached reads and an edit. It is skipped, not failed,
  when those are not running. H2 with caching off hides both PostgreSQL-specific SQL problems and
  cache serialization problems; this project has had one of each.
- **`LessonContentVisibilityTest`** checks that the public catalog never carries content URLs.
- **`PaymentRetryTest`** checks that a declined card and a withdrawal both leave a way forward.
- **`ArchitectureTest`** enforces the module boundaries described above: no cycles between
  features, every feature's repositories private to it, and no feature reaching into the
  catalog's entities.

## Configuration

Everything has a working default; override with environment variables.

| Variable | Default | |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost:5432/lms`, `lms`/`lms` | |
| `REDIS_HOST`, `REDIS_PORT` | `localhost:6379` | course detail cache, rate-limit counters |
| `JWT_SECRET` | placeholder | **change this**; at least 32 bytes |
| `JWT_ACCESS_TTL` | `PT1H` | |
| `RATE_LIMIT_REQUESTS`, `RATE_LIMIT_WINDOW` | `300`, `1m` | per token, or per IP when anonymous |
| `DB_POOL_SIZE` | `20` | must exceed request threads plus the async notification pool |
| `SMTP_HOST` | empty | leave empty and mail is written to the log instead of sent |

If Redis goes away, the cache misses and rate limiting lets requests through rather than taking the
API down with it. If SMTP is not configured, notifications are logged — obvious in the logs, and no
reason for an enrolment to fail.

## Known shortcuts

Searchable in the code as `shortcut:`.

- `POST /payments/{id}/confirm` stands in for a provider callback. A real integration would be a
  webhook with a verified signature rather than an authenticated caller.
- Rate limiting is a fixed window, so a caller can land twice the limit across a window boundary.
- A course reports revenue in one currency; revenue in a second one is logged and dropped rather
  than converted. Platform revenue is reported per currency and never summed across them.
- Reported revenue is gross. A refund updates the payment but is not deducted from the totals.
- Trimming a course re-derives each learner's progress, but the historical completion counts in
  reporting are not revised. A completion already counted stays counted.
- `RateLimitFilter` keys anonymous callers on `request.getRemoteAddr()`. Behind a load balancer
  that is the balancer's address, so everyone anonymous shares one bucket. Set
  `server.forward-headers-strategy=framework` once you have a proxy you trust.
