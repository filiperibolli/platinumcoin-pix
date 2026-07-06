# Step 21 — Transactional outbox write

## Objective
The DEBITED transition and the `PixDebited` outbox item are written in **one `TransactWriteItems`** on `pix_transactions` (tx META update + OUTBOX put). Same for later transitions (SETTLED/REVERSED, wired in their steps). Nothing publishes yet — that's step 22.

## Why / what you'll learn
The dual-write problem in the flesh (ADR-0004): if you update status and then publish, a crash in between loses the event → settlement never runs → money stuck in clearing. Committing state+event atomically in the database removes the window entirely; single-table design makes it natural (both items share the `TX#` partition). You'll build the event envelope (eventId, eventType, occurredAt, correlationId, payload) that every consumer will rely on.

## Prerequisites
Step 20.

## Tasks
1. Event envelope record in common-lib + JSON payload conventions (PascalCase types per CLAUDE.md).
2. `TransactionRepository.transition(txId, from, to, outboxEvent)` — TransactWriteItems: guarded META update (`#status = :from`) + OUTBOX put (`attribute_not_exists`).
3. Refactor step-20 orchestrator to use it for RECEIVED→DEBITED emitting `PixDebited{txId,endToEndId,amountCents,creditorInternal,debtorAccountId}`.
4. Rejection transitions also emit events (`PixRejected`) — the audit trail wants everything.

## Tests (TDD)
- `OutboxTransactionIT` — after transition: META shows DEBITED **and** OUTBOX item exists — assert both-or-neither by forcing the guard to fail (wrong `from`) and verifying no outbox item appeared.
- Envelope serialization round-trip; eventId uniqueness.

## Verify locally
```bash
# send a pix, then:
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
 --key-condition-expression 'pk = :p' --expression-attribute-values '{":p":{"S":"TX#<txId>"}}' | jq '.Items[].sk'
# → "META" and "OUTBOX#evt-..."
```

## Definition of Done
- [ ] State change and event are literally atomic (same transaction)
- [ ] Guarded transitions prevent illegal jumps
- [ ] All send-path transitions emit envelope-conformant events

## CHANGELOG entry
`### Added` → `Transactional outbox: status transitions and domain events committed in one TransactWriteItems (step 21)`
