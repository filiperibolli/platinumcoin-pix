# Step 58 — Concept: clean/hexagonal-lite + explicit use cases  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-58-clean-architecture-lite.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how every service is built inside** — the inward dependency rule, ports only for outbound infra, one use case per inbound operation, and why an ArchUnit test makes it a build failure to break the rule.

## Prerequisite
Step 09 checked in `PLAN.md` (account-service — the first service that fully follows the pattern, and whose `AccountArchitectureTest` is the template).

## Sources to consult (then close them and write from memory)
- `docs/adr/0010-clean-architecture-lite.md` and `docs/adr/0011-explicit-use-case-layer.md`
- `ARCHITECTURE.md` §3 ("Inside a service — clean/hexagonal-lite")
- CLAUDE.md → "Conventions" (the `api/` · `domain/` · `infra/` rules)
- The code: `services/account-service` package layout + `AccountArchitectureTest`

## What your write-up must address
1. The **dependency rule points inward**: `api → domain ← infra`, and `domain` imports nothing framework/AWS/servlet/Jackson. Why this makes the money logic testable as plain Java.
2. **Ports only for outbound infra**; a use case is a **class, never an interface**. Why that distinction exists.
3. The **two ArchUnit rules** and specifically why "`api/` never depends on an interface in `domain/`" is what makes "a controller may not reach a port" a *build failure* (not a code-review nicety).
4. Why it is the **lite** variant: DTO only when the wire shape diverges; a controller does exactly three things (bind+validate, call one use case, map result/exception).

## Deliverable — `docs/concepts/concept-58-clean-architecture-lite.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially why the api→domain-interface rule enforces the port boundary); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-58-clean-architecture-lite.md` written by hand
- [ ] The two ArchUnit rules and what each prevents are stated correctly
- [ ] The "use case is a class, port is an interface" distinction is explained, not just asserted
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: clean/hex-lite + explicit use cases — own-words design defense (step 58)`
