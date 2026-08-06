# Step 62 — Concept: correlation-id observability (one-grep trace)  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-62-observability-correlation-id.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how one `grep <correlationId>` reconstructs a transaction's full path across every service** — and why the id is in the log *pattern*, not in a log line.

## Prerequisite
Step 44 checked in `PLAN.md` (Prometheus/Grafana + silence alerts + `scripts/trace.sh`).

## Sources to consult (then close them and write from memory)
- `docs/adr/0012-verbose-logs-with-real-values.md`
- `ARCHITECTURE.md` §7.7 (observability) and §6.11 (the observability flow)
- CLAUDE.md → "Logging (ADR-0012)"
- The code: common-lib `logback-spring.xml` (`LOG_CORRELATION_PATTERN`), the correlation-id filter (step 02), `scripts/trace.sh`

## What your write-up must address
1. Why the id is set in the **log pattern** (`[cid=… tx=…]`) via common-lib, so *every* record — ours, Spring's, the AWS SDK's — carries it with zero per-service wiring. Why a "a request happened" filter-log is the wrong way to surface it.
2. How the id is generated at the edge, propagated by header + MDC to downstream calls and event envelopes, so the trace crosses service boundaries and consumers.
3. The message contract: an **English sentence then `key=value`**; INFO alone tells the full story of a call; the level ladder (WARN = degradations + every 4xx; ERROR = actionable only).
4. **Silence alerts** — detecting the *absence* of expected events — and the sandbox LGPD trade-off (real values in the clear, never secrets).

## Deliverable — `docs/concepts/concept-62-observability-correlation-id.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially "id in the pattern, not a log line"); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-62-observability-correlation-id.md` written by hand
- [ ] "Id in the pattern, inherited by depending on common-lib" is explained, not just stated
- [ ] The silence-alert idea and the sandbox LGPD trade-off are named
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: correlation-id observability — own-words design defense (step 62)`
