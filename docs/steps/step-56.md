# Step 56 — Concept: debited account from the JWT, never the payload  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-56-source-account-from-token.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **why the debited account is derived exclusively from the JWT `accountId` claim** and why the request body has no source-account field at all (Question 1).

## Prerequisite
Step 18 checked in `PLAN.md` (the send walking skeleton that fixes the debtor from the token).

## Sources to consult (then close them and write from memory)
- CLAUDE.md → "Domain safety rules" #1
- `docs/adr/0007-auth-service-jwt-no-mfa.md`
- `ARCHITECTURE.md` §6.4 (Question 1 in action) and §7.6 (security & audit)
- The code: the send request record + controller/use case (step 18) — confirm the source field truly does not exist on the wire shape

## What your write-up must address
1. Where authority comes from: the token's `accountId` claim, validated by common-lib's filter — not anything the client sends.
2. Why "**make it inexpressible**" (no source field on the request record) is stronger than "validate a client-supplied source field against the token".
3. The attack this rules out: a caller trying to debit **someone else's** account by naming it in the body.
4. How this interacts with the MFA/limit seam (ADR-0007) and the rest of the security posture (§7.6).

## Deliverable — `docs/concepts/concept-56-source-account-from-token.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception; close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-56-source-account-from-token.md` written by hand
- [ ] The "inexpressible vs validated" distinction is stated and defended
- [ ] The concrete attack it prevents is named
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: debited account from the JWT — own-words design defense (step 56)`
