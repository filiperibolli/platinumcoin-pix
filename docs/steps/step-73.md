# Step 73 — Concept: adversarial testing — fault injection, races & conservation  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-73-adversarial-testing.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)
>
> **Numbered out of order.** Sprint 15's concept steps are 54-63; this one was added on **2026-08-23**,
> after that block was written, and takes the next free number (65-72 belong to Sprint 11.5, as does 64).
> Same convention step 64 and steps 65-72 already follow.

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or
> autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one
> Socratic question. See CLAUDE.md → "Hand-written zones".

> **Why this step exists — read this before starting.** [Step 69](step-69.md) was originally a ✍️ zone:
> the human was to hand-write the recovery-and-fencing invariant suite, because a claim about crash
> safety is worth exactly what its adversarial test is worth, and building the trap is how you learn
> where the window is. That assignment was **reassigned on 2026-08-23** — Claude writes the suite, and
> the deliberate practice moves here, to explaining it.
>
> Be honest with yourself about what that trade costs. Building a trap and explaining one are different
> skills, and this step only exercises the second. What makes it worth doing anyway is the constraint
> below: you must be able to say, for every injection point in that suite, **why that instant and not
> the one beside it** — and that question cannot be answered by having read the code. It is answered by
> understanding what each fault window actually is. If you find yourself unable to answer it for a
> scenario, the honest move is to say so in the write-up (see "the escape hatch") rather than to
> paraphrase the comment Claude left there.

## Objective
Explain, in your own words, **how this platform proves its distributed-failure claims** — where each
fault is injected and why that instant, what a race drill must repeat and why one green run proves
nothing, and why every scenario asserts conservation of money instead of the outcome of the call.

## Prerequisite
Step 69 checked in `PLAN.md` — the suite must exist and be green, because it is the subject.

## Sources to consult (then close them and write from memory)
- The suite itself: `RecoveryInvariantsIT`, `FencingInvariantsIT`, `LateralAccessIT` and their shared
  conservation helper (step 69) — **read every injection point and its "why that instant" comment**
- [Step 69](step-69.md)'s scenario list (A-G) and its Definition of Done
- `docs/steps/step-15.md` + the step-15 ledger invariant storm — the single-service ancestor of this
  suite, and the one you *did* hand-write
- ADR-0014 (durable operation identity), ADR-0015 (timeout as unknown result), ADR-0016 (finalization
  fencing), ADR-0017 (workload identity) — each names the window its step closes
- `CHANGELOG.md`, steps 65-69 — in particular any residual-window finding recorded there

## What your write-up must address
1. **The injection point is the whole game.** Take scenario A (crash after commit, before record) and
   name the window precisely: what has already happened, what has not, and what a resume must therefore
   be able to reconstruct. Then say what the test would stop catching if the kill moved one statement
   earlier, and one statement later. Do the same for scenario D (crash *inside* the fence).
2. **Why conservation, not the outcome.** Explain why `Σ balanceCents` is the assertion of last resort,
   and — this is the sharp part — give the case where **Σ is conserved and money was still created**.
   (Step 67's CHANGELOG entry states it outright; make sure you can reconstruct *why* before you look.)
   Then say what a scenario must assert *in addition* to Σ to catch that case.
3. **Why a race drill repeats.** Scenario C releases two paths with one latch and runs N times. Explain
   what a single green run does and does not establish, and what you would conclude from a failure that
   appears once in twenty runs — including why "flaky, re-run it" is the wrong conclusion here
   specifically, when it is sometimes the right one elsewhere.
4. **Why a refusal must be checked for side effects.** Scenario E asserts `403` *and* that no ledger
   entry was written. Explain what class of bug the second half catches that the first half cannot, and
   why that split matters more on `/internal/**` than on a public route.
5. **What is still open.** Name at least one residual window the suite does *not* close, or state
   plainly that you believe it closes all of them and say what would convince you otherwise. An honest
   "here is what is still open" is worth more than a confident "all green".

## The escape hatch — use it, it is not a failure
If, for a given injection point, you cannot answer *why that instant* from your own understanding, write
that down explicitly: name the scenario, say what you do not yet see, and say what you would need to
read or run to see it. That sentence is a **better** deliverable than a fluent paraphrase of Claude's
comment, and it is exactly the kind of thing the review will grade generously. The purpose of this step
is to find the gaps the reassignment of step 69 created — not to hide them.

## Deliverable — `docs/concepts/concept-73-adversarial-testing.md`
Follow the shape in `docs/concepts/README.md`: one-sentence summary, where it lives, in-my-own-words
mechanism + why, the trade-off, the failure mode it prevents, optional open question. ~400-700 words
(longer than the other concept docs — it covers a suite, not a single mechanism).

## Claude's role (after you finish)
Review + grade (1-5) against the suite and the ADRs; flag any misconception, any injection point whose
purpose is misread, and any place where the write-up restates a code comment rather than the reasoning
behind it. **Grade the escape-hatch admissions as strengths, not gaps.** Close with **one Socratic
question** that requires synthesis, not recall.

## Definition of Done
- [ ] `concept-73-adversarial-testing.md` written by hand
- [ ] Scenarios A and D each have their window named, with the "one statement earlier / later" analysis
- [ ] The Σ-conserved-but-money-created case is stated correctly, with the extra assertion that catches it
- [ ] The repeat-the-race reasoning is explicit, including why "flaky" is the wrong read here
- [ ] At least one residual window named — or a stated case for why none remains
- [ ] Claude review done; graded; Socratic question answered (or noted for later)

## CHANGELOG entry
`### Added` → `Concept doc: adversarial testing — fault-injection points, race drills and conservation as the assertion of last resort; own-words design defense over the step-69 suite (step 73)`
