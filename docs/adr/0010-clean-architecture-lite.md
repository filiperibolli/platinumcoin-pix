# ADR-0010: Clean/hexagonal-lite architecture inside each service

**Status:** Accepted · **Date:** 2026-07-19

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
