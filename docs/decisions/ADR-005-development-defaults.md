# ADR-005: Development conveniences fail the application rather than warn it

**Status:** accepted

## Context

Two settings make this project pleasant to run locally and dangerous anywhere else:

- `lms.security.jwt.secret` has a working default, committed to the repository. Anyone holding it
  can mint a token for any user, including an admin.
- The endpoints that stand in for a payment provider let the signed-in learner settle their own
  charge, which activates a paid enrolment.

Both were documented as shortcuts. Documentation does not stop a deployment.

## Decision

`DeploymentSafetyCheck` runs at startup. Outside a development profile it throws, and the
application does not come up, if either the example signing key is in use or simulated settlement
is enabled.

The stand-in payment endpoints live in their own controller behind
`@ConditionalOnProperty`, so when the flag is off they are not merely forbidden — they do not
exist and are absent from the OpenAPI document.

Development has to be asked for by name: `dev`, `local`, `test`, `schema` or `realinfra`. Running
with **no** profile counts as production. That is the inversion that matters, because no profile is
the configuration most likely to reach a server by accident, and treating it as development would
have given the riskiest case the biggest exemption.

## Consequences

- `mvn spring-boot:run` needs `-Dspring-boot.run.profiles=dev`, and the README says so.
- `docker-compose.yml` sets `SPRING_PROFILES_ACTIVE=dev`, because it is the local demo.
- A real deployment cannot start on the example key. The failure message says how to generate one.
- Anyone wiring in a real payment provider deletes `SimulatedSettlementController` and adds a
  webhook that verifies the provider's signature and carries no caller identity at all.
