# `infra/iam/` — least-privilege role policies (ADR-0013)

One IAM policy per service, the permissions its deployed role would carry. Committed here as
**versioned artifacts rather than prose**: reviewable, diffable, and directly usable by the
Terraform/CDK stack that would deploy this platform.

## Read this first: nothing here is enforced locally

LocalStack **emulates** the IAM/STS APIs — `create-role`, `assume-role`, `get-caller-identity` all
answer — and **enforces nothing by default**. Authorization requires `ENFORCE_IAM=1`, which is off by
default and gated as a paid feature; the project runs the pinned community image (ADR-0001).

So:

- **No test in this repository can fail because a policy here is wrong.** These files are reviewed as
  documents. Their real validation is the day they are applied to an account.
- **"It works locally" is not evidence a policy is right** — locally *every* call is allowed, including
  the ones these policies exist to deny.
- The same is true of the **SQS queue resource policies** written by `infra/localstack/init/*.sh`
  (step 26): `pix-events` is the only principal allowed to `sqs:SendMessage`, guarded by `ArnEquals` on
  `aws:SourceArn`. Correct and necessary on real AWS, accepted and ignored by the emulator.

That is stated plainly rather than papered over with a test that would only assert the files parse.
Since nothing can prove them, they are **least-privilege by construction** instead: written from what
each service's code actually calls, not from what would be convenient.

## What the fan-out design buys

The policies are genuinely small because the architecture made them small (ADR-0004):

| Service | Writes | Reads | Notably **cannot** |
|---|---|---|---|
| account-service | `pix_keys` | `pix_accounts`, `pix_keys` | touch the ledger or any queue |
| ledger-service | `pix_ledger` | `pix_ledger`, `pix-statement-archive` | touch any queue or topic |
| payment-service | `pix_transactions`, `pix_idempotency` | its own two tables only — it reaches the ledger over HTTP | `sqs:*` — it publishes, it never consumes |
| settlement-service | `pix_transactions`, `pix-audit-log` | `pix_processed_events` | `sns:Publish` — it consumes, it never publishes |
| notification-service | `pix_processed_events` | `notification-queue` | read any account, ledger or transaction table |

The two "cannot" rows on the money path are the ones worth defending in review. payment-service having
**no SQS permission at all** and settlement-service having **no SNS permission at all** is what makes
the outbox topology a security boundary and not just a diagram: a compromised publisher cannot drain a
consumer queue, and a compromised consumer cannot fan a forged event out to every subscriber.

Likewise, notification-service's policy grants **no table but the shared dedup one** — the payoff of
putting `creditorAccountId` and `amountCents` in the event payload itself. A service that needs no
customer data is a service whose credential is worth nothing to steal.

## Conventions

- Account id and region are `${AWS_ACCOUNT_ID}` / `${AWS_REGION}` placeholders — resolved by whatever
  applies them. A literal `123456789012` in a committed policy invites a copy-paste into the wrong
  account.
- Resources are **concrete ARNs**, never `"Resource": "*"`. A table ARN is followed by its
  `/index/<name>` sibling wherever the code queries a GSI: DynamoDB treats an index as a separate
  resource, and a policy that grants the table alone fails at the first `Query` on `gsi1` — the single
  most common way a least-privilege DynamoDB policy is wrong.
- Actions are the SDK calls the code actually makes. `dynamodb:TransactWriteItems` is listed wherever a
  `TransactWriteItems` spans two tables, and it must be granted **on every table in the transaction** or
  the whole transaction is denied.
- KMS is not modelled: the platform uses AWS-owned keys. A production deployment with a CMK adds
  `kms:Decrypt`/`kms:GenerateDataKey` on that key ARN to every service that touches the encrypted
  resource, which is a real and easily-missed sixth statement.

## What each statement is for

The policies themselves are plain, valid IAM — no comment keys, because `aws iam create-policy` rejects
unknown fields and a policy that has to be edited before it can be applied is not the artifact ADR-0013
asked for. The reasoning lives here instead, keyed by `Sid`.

### account-service

- **`ReadTheAccountDirectory`** — `GET /v1/accounts/me` and the internal `accounts:read` port.
  Read-only: no endpoint in this service writes an account. A daily limit is changed by an operator flow
  that does not exist yet, and granting the write now would be granting it to nobody.
- **`OwnThePixKeyDirectory`** — register (conditional `PutItem`), list (`Query` on the owner index),
  delete, and the internal `keys:resolve` lookup. Note the separate `/index/gsi1` ARN.

### ledger-service

- **`OwnTheLedger`** — the only role in the platform with *any* `pix_ledger` permission (ADR-0006);
  every other service reaches the ledger over HTTP through the internal port.
  `dynamodb:TransactWriteItems` is the double-entry posting — debit leg, credit leg, the
  `balanceCents >= :amount` conditions and the `txId` guard item, all one call — and it is denied
  outright unless granted on every table it touches.
- **`WriteAndReadTheStatementColdArchive` / `ListOnlyTheArchiveBucket`** — step 43. `PutObject` is
  granted here and deliberately *not* on the audit bucket (below), because the archive holds derived,
  rebuildable data whose monthly object is rewritten as the window rolls; the ledger stays the source of
  truth. No `DeleteObject`: an archive this service can erase is not an archive.

### payment-service

- **`OwnTransactionsAndTheirOutbox`** — the transaction `META` item and the `OUTBOX#` items sharing its
  partition; one `TransactWriteItems` writes both, which is the whole point of the outbox (ADR-0004).
  `gsi1` is the `endToEndId` lookup, `gsi2` the stuck-transaction scan, `gsi3` the sparse
  unpublished-events index the publisher drains — three separate resource ARNs.
- **`OwnTheIdempotencyClaims`** — ADR-0002/ADR-0014. The conditional `PutItem` *is* the claim, so the
  write permission is not a convenience: it is the mechanism that makes a retried `POST` safe.
- **`PublishToPixEventsAndNothingElse`** — one topic ARN, one action. The service is *handed* the ARN as
  configuration (`pix.events.topic-arn`) rather than discovering it, so it needs neither
  `sns:ListTopics` nor `sns:CreateTopic`: a deployed service has no business enumerating the account's
  topics.
- **What is absent:** no `sqs:*` statement of any kind. payment-service publishes and never consumes, so
  a compromised payment-service cannot drain `settlement-queue`, `notification-queue` or `audit-queue`.
  No `pix_ledger` permission either — it moves money by calling ledger-service's internal port
  (ADR-0006), so the ledger's blast radius does not grow when this service is breached.

### settlement-service

- **`DriveTheGuardedTransitions`** — the narrow write surface on a table payment-service owns (ADR-0006's
  documented exception): the guarded status transitions, the fencing CAS (ADR-0016), and the outbox items
  that commit with them.
- **`UseTheSharedConsumerDedupGate`** — step 29's claim/release gate. `DeleteItem` is required and is
  easy to mistake for over-permission: a consumer whose work *failed* must release its claim, or the SQS
  redelivery is deduped away and the payment never settles.
- **`ConsumeTheSettlementAndAuditQueues`** — `ChangeMessageVisibility` belongs with `Receive`/`Delete`:
  without it a consumer cannot extend the visibility window on a slow rail call, and the message is
  redelivered while the first attempt is still in flight. `GetQueueUrl` is how the queue *name* in
  config becomes a URL at startup. No `sqs:SendMessage` — this service never enqueues.
- **`AppendToTheImmutableAuditTrail`** — `PutObject` only. No `GetObject`, no `DeleteObject`, and
  emphatically no `PutObjectRetention`: the bucket's own COMPLIANCE-mode Object Lock stamps the retention
  date, and a writer that could set or shorten it would be the hole the lock exists to close.
- **What is absent:** no `sns:Publish`. settlement-service writes its events into the outbox partition of
  `pix_transactions` and payment-service's publisher drains them, so a compromised settlement-service
  cannot fan a forged `PixSettled` out to every subscriber. Together with payment-service having no
  `sqs:*`, this is what makes the outbox topology an authorization boundary and not merely a diagram.

### notification-service

- **`ConsumeTheNotificationQueue`** — the same consumer shape, on one queue.
- **`UseTheSharedConsumerDedupGate`** — the only table this service touches at all.
- **What is absent:** no account, ledger or transaction table. That is a design payoff, not a restriction
  endured: `creditorAccountId` and `amountCents` travel *in* the event, so routing and rendering a
  notification need no lookup. A service that needs no customer data has a credential worth little to
  steal — and, for the same reason, a directory or ledger outage cannot stop notifications.

## Services with no policy here

`auth-service`, `fraud-service` and `mock-bacen-spi` touch no AWS resource at all — auth-service is
AWS-free by design (ADR-0007), fraud-service uses Redis (ADR-0008), and the mock is a stub. A role with
an empty policy would be a file that says nothing; their absence from this directory is the statement.
