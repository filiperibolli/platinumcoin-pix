# ADR-0011: Explicit use-case layer per inbound operation

**Status:** Accepted · **Date:** 2026-08-01 · **Amends:** [ADR-0010](0010-clean-architecture-lite.md)

## Context

ADR-0010 fixed the internal shape of every service (`api/` → `domain/` ← `infra/`, domain is plain
Java) and deliberately **rejected** one piece of Clean Architecture ceremony: "a separate
application/use-case ring". The reasoning was that a pass-through class for a single-lookup
operation buys isolation we don't need and costs boilerplate that hurts reviewability.

Eleven steps in, that rule has produced **two different shapes inside the same codebase**:

- Operations with branching got a domain service — `KeyResolutionService` (step 11),
  `AuthenticationService` (step 04).
- Operations without branching had their **controller call the outbound port directly** —
  `AccountController` → `AccountRepository`, `PixKeyController` → `PixKeyRepository`,
  `InternalAccountController` → `AccountRepository`.

And in one case it produced genuine business policy inside an inbound HTTP adapter.
`PixKeyController.register` held:

- the rule *"EVP is server-generated; the client's `keyValue` is ignored"* as a ternary — a
  **security** rule of the same family as Domain Safety Rule #1 (the client does not choose), living
  in a Spring controller;
- `Instant.now()` read directly in the handler — an **unfakeable clock**. Harmless today, but the
  calendar-day limit counter (step 20), the stuck-transaction scanner (step 34) and the idempotency
  TTL (step 19) all make time a decision input.

Three costs follow, and they compound as the platform grows to eight services:

1. **Intent is not legible at the call site.** Reading `api/` does not tell you which operations a
   service supports; you infer them from an HTTP verb plus a repository call.
2. **Policy is only reachable through HTTP.** Testing "EVP ignores the client value" requires
   MockMvc; a second caller that is not a controller (a queue consumer, a reconciliation job, a
   batch export — all of which arrive in later sprints) cannot reuse the rule and will copy it.
3. **The judgement "does this deserve a service?" gets re-litigated per endpoint**, and the answers
   diverge. That is precisely the drift ADR-0010 set out to prevent *between services*, reappearing
   *between endpoints of one service*.

## Decision

**Every inbound operation is served by an explicit use case class in `domain/usecase/`.**

ADR-0010's rules 1, 3 and 4 stand unchanged. Rule 2 (ports only for outbound infrastructure) stands.
The following are **added**:

5. **One class per operation**, named `<Verb><Noun>UseCase`, with a single public `execute(...)`
   method. The name states the **business intent**, not the HTTP verb or the table
   (`RegisterPixKeyUseCase`, not `PixKeyPostHandler` or `PixKeyTableService`). The set of files in
   `domain/usecase/` **is** the service's capability list.

6. **`api/` must not depend on an outbound port.** A controller may depend on use cases and on
   domain records/enums (to reshape a result), never on a repository, publisher or external-client
   interface. This is the mechanical form of the rule and the one the build enforces.

7. **No business policy in `api/`.** Value normalization and generation, ownership checks,
   "not found" decisions, limit/eligibility rules and **reading the clock** belong to the use case.
   `api/` keeps exactly three jobs: bind + bean-validate the wire shape, call **one** use case, map
   the result (or a domain exception) to an HTTP status and a response record.

**Placement.** Use cases live **inside `domain/`**, in a `usecase/` sub-package — not as a fourth
top-level ring. ADR-0010's dependency rule (`api → domain ← infra`) is therefore unchanged, and a
use case is plain Java: no Spring, no AWS SDK, no servlet types. It is instantiated by `infra/`'s
composition root (`*BeansConfig`), and where it needs the current time it takes an injected
`java.time.Clock` — never `Instant.now()`.

**Domain exceptions.** A use case signals a business failure with a domain-local exception
(`PixKeyAlreadyExistsException`, `AccountNotFoundException`, …) — plain Java, no
`org.springframework.http.HttpStatus`. A `*ExceptionHandler` in `api/` maps each to its
`code` + status + `application/problem+json` body, extending the pattern auth-service already used
for `InvalidCredentialsException`. The wire contract does not change.

**Enforcement.** Each service's `*ArchitectureTest` gains a rule that fails the build when a class in
`..api..` depends on an **interface** residing in `..domain..`. Every outbound port is an interface;
every use case is a class — so the rule is exact, and needs no naming convention to work.

**Permitted exception.** A controller that touches no port and applies no policy — it only reshapes
something the framework already produced, e.g. `MeController` echoing the validated JWT principal —
needs no use case. This is not a judgement call: it is what rule 6 already allows, mechanically.

## Consequences

- **Intent is legible.** `ls domain/usecase/` answers "what can this service do?" without reading a
  controller or an OpenAPI file.
- **Policy is unit-testable as plain Java.** The EVP-generation rule, the ownership guard and the
  key-format rule become three-line unit tests with a fake port and a fixed `Clock` — no MockMvc, no
  Testcontainers. This is the benefit ADR-0010 promised and this amendment actually collects.
- **Policy is reusable by non-HTTP callers.** The SQS consumers and scheduled jobs of Sprints 6–10
  call the same use case a controller calls.
- **Time becomes injectable.** A `Clock` bean per service; tests fix it.
- **Costs accepted:** roughly one extra class per endpoint, *including* thin ones such as
  `GetMyAccountUseCase` that only delegate to a port and raise a not-found. This is the deliberate
  reversal: on this point we choose **uniformity over per-endpoint judgement**, because the
  divergence documented above cost more than the boilerplate does. ADR-0010's objection is
  acknowledged, not dismissed — the thin classes really are thin.
- **Alternative rejected — a separate top-level `application/` ring.** Truer to Clean Architecture's
  vocabulary, and it would sharpen the entity-logic vs. orchestration distinction. Rejected because
  it adds a third arrow to the dependency rule (`api → application → domain`), needs a second
  ArchUnit rule set in all eight services, and buys a separation already carried by the package name
  and the `*UseCase` suffix. Revisit if a service ever grows domain logic rich enough that
  orchestration and entity rules genuinely compete for the same package.
- **Unchanged:** ports only for outbound infrastructure; a DTO only when the wire shape diverges;
  ArchUnit enforcement per service; `common-lib` exempt (it is the shared adapter layer, with no
  `domain/` of its own); `mock-bacen-spi` and `labs/ledger-pg` may keep a thinner structure.
- **Migration.** Applied retroactively to the services built in steps 01–11 (auth-service,
  account-service) in the same change that accepts this ADR, so no service is left on the old shape.
  `AuthenticationService` → `LoginUseCase`; `KeyResolutionService` → `ResolvePixKeyUseCase`.
