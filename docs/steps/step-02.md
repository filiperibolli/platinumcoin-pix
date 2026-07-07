# Step 02 — common-lib: error model + correlation id + JSON logging

> **Sprint 1 — Foundation & Identity** · **Flow:** login → JWT · **Infra que sobe:** none · **Diagram:** ARCHITECTURE §6.1

## Objective
Shared foundations every service uses: RFC 7807 `application/problem+json` error handling with a `code` field, a servlet filter that reads/creates `X-Correlation-Id` and puts it in MDC, and structured JSON logging (logstash-logback-encoder) including `correlationId`.

## Why / what you'll learn
In a distributed system, a request touches 4+ services; the **correlation id** is the thread you pull to reconstruct what happened — generated at the edge if absent, propagated on every outgoing call, attached to every log line via MDC. Structured JSON logs make that grep-able (`jq 'select(.correlationId=="...")'`). A uniform problem+json error contract means clients (and you, debugging) parse one error shape platform-wide, and stack traces never leak. Building this *before* the first service means the identity flow is already observable.

## Prerequisites
Step 01.

## Tasks
1. `common-lib`: `ProblemDetailFactory` + `@RestControllerAdvice GlobalExceptionHandler` (maps validation → 400, `DomainException(code,status)` → its status, fallback → 500 generic, always with `correlationId`).
2. `CorrelationIdFilter` (highest precedence): read `X-Correlation-Id` or generate UUID; store in MDC key `correlationId`; echo on the response header.
3. `logback-spring.xml` shared include: JSON encoder with `timestamp, level, logger, message, correlationId, txId` fields; plain console pattern for local `dev` profile if desired.
4. `RestClientCustomizer` that propagates `X-Correlation-Id` on outgoing `RestClient` calls.
5. Wire auto-configuration (`@AutoConfiguration` + `AutoConfiguration.imports`) so services get all of this by depending on common-lib — zero per-service boilerplate.

## Tests (TDD)
- `CorrelationIdFilterTest` — absent header ⇒ generated + echoed; present ⇒ preserved; MDC cleaned after request.
- `GlobalExceptionHandlerTest` (MockMvc) — `DomainException("LIMIT_EXCEEDED",422)` ⇒ 422 problem+json with code + correlationId; unexpected exception ⇒ 500 with no stack trace in body.

## Verify locally
```bash
mvn -q -pl services/common-lib verify
# once auth-service exists (step 03), a bad request returns problem+json with the echoed correlationId
```

## Definition of Done
- [ ] Any error from any consuming service is problem+json with `code` and `correlationId`
- [ ] Correlation id generated-if-absent, echoed, and cleaned from MDC after each request
- [ ] Outgoing RestClient calls carry the header automatically
- [ ] Everything ships via auto-configuration (no per-service wiring)

## CHANGELOG entry
`### Added` → `Shared error model (RFC 7807), correlation-id propagation and structured JSON logging in common-lib (step 02)`
