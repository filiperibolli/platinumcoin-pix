# ADR-0010: Clean/hexagonal-lite architecture inside each service

**Status:** Accepted · **Date:** 2026-07-19 · **Amended by:** [ADR-0011](0011-explicit-use-case-layer.md) · **Amended 2026-08-10** (internal sub-package layout, below) · **Amended 2026-08-20** (`infra/web/` role, step 38)

> **Amendment notice.** ADR-0011 reverses this ADR on **one** point: the rejection of a use-case ring
> below. Every inbound operation now gets an explicit `<Verb><Noun>UseCase` class in
> `domain/usecase/`, `api/` may not depend on an outbound port, and no business policy lives in
> `api/`. Everything else here — the three packages, the inward dependency rule, ports-only-for-
> outbound-infra, DTO-only-when-shapes-diverge, ArchUnit enforcement, the `common-lib` exemption —
> stands as written. Read this ADR for the shape and the reasoning; read ADR-0011 for what changed
> and why the original trade-off did not hold.

> **Amendment notice (2026-08-10) — internal sub-package layout.** The three top-level packages
> `api/` · `domain/` · `infra/` stay exactly as decided; this amendment only says **how the files
> inside `domain/` and `infra/` are grouped**, because a flat `domain/` of ~25 mixed files (entities,
> ports, and a dozen exceptions side by side) had become hard to navigate. It changes nothing about
> the dependency rule or the ArchUnit tests. See "Internal package layout" under Decision.

## Context
ADR-0006 decides how the system splits **across** services. It says nothing about how a single
service is structured **inside**. Without a stated rule, eight services drift into eight different
internal shapes, and the domain logic — the part that must be provably correct for money — ends up
entangled with Spring, the AWS SDK and the servlet API, so it can only be tested with a container up.

We want the core benefit of Clean Architecture / hexagonal (ports & adapters): **the domain is plain
Java, isolated from frameworks and infrastructure, and depends on nothing that points outward.** We do
**not** want its ceremony — a separate application/use-case ring, a DTO+mapper pair at every boundary
even when the shapes are identical, and an interface for every collaborator regardless of need. That
ceremony buys isolation we don't need at this size and costs boilerplate that hurts reviewability,
which this project values over cleverness.

## Decision
Every service module follows the same three-package internal layout under
`com.platinumcoin.pix.<service>`:

- **`api/`** — inbound adapters: controllers, request/response records, exception mapping.
- **`domain/`** — entities & value objects (records), domain services (the use-case logic), and
  **ports** (outbound interfaces the domain calls). Plain Java only.
- **`infra/`** — outbound adapters implementing the ports (DynamoDB/SQS/SNS/Redis/HTTP), plus Spring
  configuration and wiring.

### Internal package layout (amendment 2026-08-10)

Inside `domain/` and `infra/`, files are **grouped by role** into a fixed set of sub-packages —
**one folder per role, always, even when a role holds a single file.** Uniformity beats a size
threshold: a reader learns one shape and it holds for every service, and there is never a "does this
belong in a folder yet?" judgement call. This is navigation only — the dependency rule and the
ArchUnit tests are unchanged, because their matchers already target the whole subtree (`..domain..`,
`..api..`, `..infra..`), so a file keeps satisfying them wherever inside its layer it lives. **No
`.java` sits loose at the root of `domain/` or `infra/`** (only `Application.java` stays at the
service-package root, as the Spring entry point).

- **`domain/model/`** — entities & value objects (records/enums): `Transaction`, `Money`,
  `IdempotencyRecord`, `TransactionStatus`, etc.
- **`domain/port/`** — the outbound interfaces the domain drives: repositories, external clients,
  publishers (`TransactionRepository`, `LedgerClient`, `PixKeyResolver`, …). A lone port still lives
  here (`LedgerRepository` in ledger-service is the only port — it goes in `port/` all the same). Ports
  are **not** further split into repository/client sub-packages; the ArchUnit `api → interface-in-
  domain` ban already treats them uniformly.
- **`domain/exception/`** — the plain-Java domain exceptions (`InsufficientFundsException`,
  `LimitExceededException`, …); a single exception (auth-service's `InvalidCredentialsException`) gets
  the folder too.
- **`domain/service/`** — concrete, framework-free **domain services / helpers** that are neither a
  value object nor a use case (`EndToEndIdGenerator` in payment-service, `AccountPolicy` in
  ledger-service). Plain Java, injected into use cases; distinct from `usecase/` because they are not a
  `<Verb><Noun>UseCase` inbound operation.
- **`domain/usecase/`** — the `<Verb><Noun>UseCase` classes (ADR-0011) **and their command/outcome
  records** (`SendPixCommand`, `SendPixOutcome`), which are use-case-scoped, not shared model.
- **`infra/persistence/`** — datastore adapters implementing repository ports (`DynamoTransactionRepository`,
  `DynamoIdempotencyRepository`, and in-memory stand-ins like auth's `InMemoryUserRepository`).
- **`infra/client/`** — HTTP/SPI adapters implementing external-service client ports
  (`HttpLedgerClient`, `HttpPixKeyResolver`, `HttpAccountLimitClient`, …).
- **`infra/security/`** — crypto/token/security adapters implementing security ports
  (`BCryptPasswordVerifier`, `JwtIssuer` in auth-service, `SseTokenHandshakeFilter` in
  notification-service). Present in services that have such adapters.
- **`infra/web/`** — outbound **transport** adapters: the ones that write *to a client* rather than to a
  datastore or another service (`SseEmitterRegistry` in notification-service). **Added 2026-08-20 by
  step 38**, when the platform grew its first long-lived-connection service and the four existing roles
  had no honest home for it: a registry of live SSE emitters is not persistence (nothing is durable), not
  a `client/` (it calls no external service), not security and not config. Present only in services that
  push to clients.
- **`infra/config/`** — Spring configuration and `@ConfigurationProperties` (`*BeansConfig`,
  `DynamoConfig`, `CorsConfig`, `AwsProperties`, …).

A service only carries the folders for roles it actually has (account-service has no `infra/client/`;
auth-service has no `infra/client/` but does have `infra/security/`). What is uniform is: **when a role
exists, it is a folder, whatever the count.** `common-lib`, `mock-bacen-spi` and `labs/ledger-pg` keep
the thinner structure the scope note below already grants them (a shared adapter layer, a stub, a lab).

Governed by four rules:

1. **The dependency rule points inward.** `api → domain` and `infra → domain`; **`domain` depends on
   nothing outward.** Domain code must not import Spring (`org.springframework.web.*`),
   the AWS SDK (`software.amazon.awssdk.*`), the servlet API (`jakarta.servlet.*`) or Jackson
   binding. It is framework- and infrastructure-agnostic.
2. **Ports only for outbound infrastructure.** The domain declares an interface for each **external**
   dependency it drives — repositories, external clients (SPI, fraud), event publishers — and an
   `infra/` adapter implements it. No port for collaborators that live entirely inside the domain,
   and no interface that will only ever have one implementation *and* is not a boundary.
3. **A DTO only when the wire shape diverges** from the domain type. If a controller's request/response
   is structurally identical to a domain record, reuse it; introduce a separate `api/` record + mapper
   only where the external contract genuinely differs (field names, masking, formatting money to a
   decimal string at the edge). No mirror-DTO-per-entity by reflex.
4. **The rule is enforced, not merely asked.** Each service ships **one ArchUnit test** (`*ArchitectureTest`)
   from its first step that fails the build if `domain/` imports Spring-web, the AWS SDK, the servlet API
   or Jackson binding, and that asserts the `api → domain ← infra` direction. This turns the dependency
   rule from a comment into a verifiable invariant — consistent with the project's "if it can't be
   verified by a test, it's too vague" principle.

**Scope.** These rules apply to the **service modules** (which have a `domain/`). `common-lib` is
exempt: it *is* the shared adapter/utility layer (error model, correlation filter, JWT, logging), so
it legitimately depends on Spring-web and the servlet API. `mock-bacen-spi` and `labs/ledger-pg` may
keep a thinner structure appropriate to a stub / a lab.

## Consequences
- **Domain testable without infrastructure.** Money invariants (ADR-0001/0002) are unit-tested against
  plain objects; Testcontainers is reserved for the `infra/` adapters (`*IT`). Faster, sharper tests.
- **Adapters are swappable at the port.** This is not theoretical here: ADR-0009's `labs/ledger-pg`
  reuses the ledger's domain port against PostgreSQL — the port boundary is what makes that lab possible
  without touching domain code.
- **One review rule for all eight services**, and a build that fails on violation instead of relying on
  a reviewer to spot a stray `import software.amazon.awssdk`.
- **Costs accepted:** some boundary boilerplate where shapes diverge; discipline to keep domain services
  from reaching for framework conveniences; one ArchUnit test to maintain per service. The "lite"
  boundaries above cap that cost deliberately — we stop short of full Clean Architecture.
- Supersedes the one-line "Hexagonal-lite" note previously in `CLAUDE.md`, which this ADR now defines.
