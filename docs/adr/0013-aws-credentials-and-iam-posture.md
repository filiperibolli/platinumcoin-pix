# ADR-0013: AWS credentials & IAM posture (local emulation vs. production)

**Status:** Accepted · **Date:** 2026-08-11 · **Implementation:** step 45 (security checklist sweep)

## Context
Every service that touches AWS builds its SDK client with a `StaticCredentialsProvider` carrying
`AWS_ACCESS_KEY_ID=test` / `AWS_SECRET_ACCESS_KEY=test` (see `DynamoConfig` in account-, ledger- and
payment-service). That is not an abstraction of production authentication — it is a **signing
formality**: LocalStack validates no signature and only reads the access key to derive the account id
(`000000000000`). Step 26 added a second, subtler case of the same thing: the SQS queue's resource
`Policy` (only `pix-events`, only `sqs:SendMessage`, guarded by `ArnEquals` on `aws:SourceArn`) is
**correct and necessary on real AWS but unenforced by the emulator**.

Two facts about the emulator bound what is possible here:
- LocalStack **does** emulate the IAM/STS APIs — roles, policies, `assume-role`, `get-caller-identity`
  all respond.
- LocalStack does **not** enforce them by default: enforcement requires `ENFORCE_IAM=1`, is off by
  default ("all APIs can be accessed without authentication"), and is gated as a paid feature. The
  project runs the pinned community image (ADR-0001).

So locally we can *model* IAM; we cannot *prove denial*. The risk this ADR addresses is not that the
local credentials are fake — it is that their **shape** in the code is indistinguishable from the
production anti-pattern (a long-lived key/secret pair baked into a service), leaving a reader unable
to tell a deliberate local override from a misunderstanding.

## Decision
1. **No long-lived credential on the production code path.** The default client build takes neither
   `endpointOverride` nor `credentialsProvider`, letting the SDK's `DefaultCredentialsProvider` chain
   resolve the ambient role: ECS task role, EKS IRSA/Pod Identity, or EC2 instance profile. All three
   hand out **temporary STS credentials that the SDK rotates on its own**.
2. **The local path is an explicit, isolated exception.** Static credentials + `endpointOverride` live
   behind a `local` Spring profile in the one AWS-client configuration class per service, commented as
   the LocalStack override. One shape, one place, obviously deliberate.
3. **IAM policies are versioned artifacts, not prose.** `infra/iam/<service>-policy.json` per service,
   least-privilege, with concrete ARNs and conditions — reviewable, diffable, and directly usable by a
   Terraform/CDK stack. The fan-out design makes them genuinely small: payment-service needs
   `sns:Publish` on one topic ARN and *no* SQS permission at all; settlement-service needs
   `sqs:ReceiveMessage`/`DeleteMessage`/`ChangeMessageVisibility` on one queue and *no* SNS permission.
   Adding notification-queue (step 36) changes neither.
4. **What the emulator cannot enforce is documented where it is written, not hidden.** The queue
   `Policy` of step 26 and these role policies are marked as production-semantics artifacts; the local
   stack accepts every call regardless.
5. **Scheduled, not retrofitted piecemeal.** The sweep lands in **step 45** (which already owns the
   security checklist), across every AWS client at once. Until then new clients — starting with the
   `SnsClient` of step 29 — **copy the existing shape**: two competing shapes mid-migration is worse
   than one uniform shape awaiting a single, reviewable change.

## Alternatives rejected
- **Roles created in the init script + `sts assume-role` on the local path.** Without `ENFORCE_IAM`
  the assumed credential works no matter what the policy says, so the ceremony proves nothing while
  adding a moving part to every service's boot. Reconsider only alongside enforcement, where the
  negative test ("service A *cannot* publish to topic B") becomes writable — that test is the only
  thing this option would actually buy.
- **`ENFORCE_IAM=1` on a paid LocalStack tier.** Buys real deny semantics and the negative test, at a
  licence plus a per-request policy-evaluation cost on every local run and CI job. Out of scope for a
  project whose emulator baseline is the pinned community image (ADR-0001).
- **A smoke test against a real AWS account** to prove the role path resolves. It would validate AWS,
  not this design, and directly contradicts the project's "100% local, no registry, no Kubernetes"
  constraint (`CLAUDE.md`). If ever wanted, it belongs in a separate throwaway repo — one task, one
  topic, `get-caller-identity` + `sns:Publish` — never wired into this stack.
- **Leaving it as is.** Cheapest, and the reason it is rejected is not correctness (the local stack
  works) but reviewability: the code would keep asserting a posture the author does not hold.

## Consequences
- Local runs need the `local` profile active (compose sets it; `docs/local-dev.md` documents the
  single-service `spring-boot:run` case), and a service started without it will correctly fail to find
  credentials instead of silently talking to LocalStack.
- The policy JSONs are **unverified by any local test** — nothing in the suite can fail when one is
  wrong. They are reviewed as documents; their real validation is the day they are applied to an
  account. This is stated plainly rather than papered over with a test that would only assert the file
  parses.
- Two artifacts in this repo carry production semantics the emulator ignores (the step-26 queue policy
  and these role policies). Anyone reading them must know that "it works locally" is not evidence they
  are right — which is exactly why they are least-privilege by construction rather than by testing.
