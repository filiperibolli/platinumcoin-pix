#!/bin/bash
# Step 42 — the THIRD consumer off pix-events, and the first object storage in the project:
# audit-queue (+DLQ) subscribed to the topic with NO filter policy, plus the two S3 buckets the
# audit trail of step 43 writes to.
#
# WHY THIS ONE HAS NO FILTER POLICY. settlement (06) and notification (08) each name the event types
# they act on — a consumer should not pay a receive for an event it will drop. Audit is the opposite
# problem: it does not *act* on events, it *records that they happened*, so any filter is a list
# somebody has to remember to extend. The day a new event type ships (FraudCheckSkipped, PixReversed,
# whatever Sprint 12 adds), a filtered audit queue would silently omit it — and a gap in an audit
# trail is discovered years later, by an auditor, not by a failing test. Absence of a filter is
# therefore a deliberate configuration decision here, asserted by MessagingInitIT.
#
# THE IMMUTABILITY POSTURE (ARCHITECTURE §6.10, docs/threat-model.md). pix-audit-log is created with
# Object Lock enabled — which forces versioning on and, in real AWS, can NEVER be enabled after
# creation — and carries a default retention of COMPLIANCE / 1825 days (5 years, BACEN). COMPLIANCE
# rather than GOVERNANCE on purpose: GOVERNANCE can be bypassed by a principal holding
# s3:BypassGovernanceRetention, i.e. exactly the privileged operator an audit trail exists to keep
# honest. Every object written inherits the retention with no caller opt-in, so a buggy or malicious
# writer cannot produce a deletable audit line.
#
# LOCALSTACK vs AWS — the honest bit. LocalStack 3 does more than accept this configuration: it
# ENFORCES it (deleting a retained version answers AccessDenied, which S3InitIT asserts). What stays
# AWS-only is everything below the API: WORM at the storage layer, surviving `docker compose down -v`
# (the emulator's state is ephemeral by design), cross-region replication of the trail, and IAM
# actually denying anything (ADR-0013 — LocalStack does not enforce IAM). So locally we prove the
# posture is configured and refused at the API; we never prove the bytes are truly immutable.
#
# pix-statement-archive is deliberately a PLAIN bucket: no versioning, no lock. It holds *derived*
# data — a cold copy of ledger entries the ledger still owns — and step 43 rewrites its monthly
# account=<id>/yyyy-MM.jsonl object as the window rolls. Locking it would pile up undeletable
# versions of a rebuildable file and buy no compliance at all.
#
# Numbered 09 so it sorts LAST → its final log line is the readiness marker the Testcontainers
# harness waits on (LocalStackTestBase) and the compose healthcheck probe asserts (docker-compose.yml
# checks the last resource this script creates). It took that role over from 08-messaging-notify.sh
# in this step. If you ever append a script after this one, MOVE BOTH MARKERS.
#
# Idempotent: create-if-absent, then always converge attributes (set-queue-attributes /
# set-subscription-attributes / put-bucket-* are idempotent, so a re-run also repairs drift).
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://localhost:4566"

awslocal() { aws --endpoint-url="$ENDPOINT" "$@"; }

# Creates the queue only if it does not exist yet; prints nothing but its URL on stdout.
create_queue_if_absent() {
  local queue="$1"
  if awslocal sqs get-queue-url --queue-name "$queue" --query QueueUrl --output text 2>/dev/null; then
    echo "[init] queue $queue already exists — skipping create" >&2
    return 0
  fi
  echo "[init] creating queue $queue" >&2
  awslocal sqs create-queue --queue-name "$queue" --query QueueUrl --output text
}

queue_arn() {
  awslocal sqs get-queue-attributes --queue-url "$1" --attribute-names QueueArn \
    --query 'Attributes.QueueArn' --output text
}

# ── SNS topic: pix-events (already created by 06; create-topic is idempotent) ──
TOPIC_ARN=$(awslocal sns create-topic --name pix-events --query TopicArn --output text)
echo "[init] SNS topic ready: $TOPIC_ARN"

# ── audit-queue-dlq (created FIRST — the main queue needs its ARN) ─────────────
# An audit line that cannot be written is evidence, not garbage: it goes to the DLQ (ADR-0003) and
# stays there for the SQS maximum of 14 days, where the DLQ-depth alert (step 44) can see it.
AUDIT_DLQ_URL=$(create_queue_if_absent audit-queue-dlq)
awslocal sqs set-queue-attributes --queue-url "$AUDIT_DLQ_URL" \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
AUDIT_DLQ_ARN=$(queue_arn "$AUDIT_DLQ_URL")
echo "[init] DLQ ready: $AUDIT_DLQ_ARN (retention 14d)"

# ── audit-queue ───────────────────────────────────────────────────────────────
# Same tuning as the other two consumers for consistency (redrive after 5 receives, visibility 30s,
# long-poll 20s). The audit writer batches (~100 events or 30s, step 43), so 30s of visibility is a
# comfortable ceiling for one flush rather than a tight bound like settlement's 12s SPI call.
AUDIT_URL=$(create_queue_if_absent audit-queue)
awslocal sqs set-queue-attributes --queue-url "$AUDIT_URL" --attributes "$(cat <<JSON
{
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$AUDIT_DLQ_ARN\",\"maxReceiveCount\":\"5\"}",
  "VisibilityTimeout": "30",
  "ReceiveMessageWaitTimeSeconds": "20"
}
JSON
)"
AUDIT_ARN=$(queue_arn "$AUDIT_URL")

# Let the topic write into the queue — narrow policy, only this topic, only SendMessage. The API
# (unlike the console) does not add this for you, and a missing policy fails SILENTLY (publish
# accepted, delivery denied, the message simply never arrives).
awslocal sqs set-queue-attributes --queue-url "$AUDIT_URL" --attributes "$(cat <<JSON
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"allow-pix-events-topic\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$AUDIT_ARN\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$TOPIC_ARN\"}}}]}"
}
JSON
)"
echo "[init] queue ready: $AUDIT_ARN (redrive → DLQ after 5 receives, visibility 30s, long-poll 20s)"

# ── subscription: pix-events → audit-queue, UNFILTERED ────────────────────────
# Guarded so a container restart does not pile up duplicate subscriptions (each duplicate would
# deliver another COPY of every event — and on this queue, "every event" means every event).
SUBSCRIPTION_ARN=$(awslocal sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" \
  --query "Subscriptions[?Endpoint=='$AUDIT_ARN'].SubscriptionArn | [0]" --output text)
if [ "$SUBSCRIPTION_ARN" = "None" ] || [ -z "$SUBSCRIPTION_ARN" ]; then
  echo "[init] subscribing audit-queue to pix-events (no filter policy — all events)"
  SUBSCRIPTION_ARN=$(awslocal sns subscribe --topic-arn "$TOPIC_ARN" \
    --protocol sqs --notification-endpoint "$AUDIT_ARN" \
    --query SubscriptionArn --output text)
else
  echo "[init] subscription already exists — converging its attributes"
fi

# No FilterPolicy is SET here — but drift is repaired explicitly: an empty attribute value REMOVES a
# filter policy someone added by hand or in an older revision of this script. Converging to "none"
# has to be an action, otherwise the idempotent re-run would silently preserve the wrong state.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy --attribute-value ''

# RawMessageDelivery — deliver the event JSON as published, not wrapped in the SNS notification
# envelope, so the archived line is the SAME envelope the publisher wrote (broker-agnostic; messaging
# appendix). An SNS-wrapped body would make the audit trail a record of our broker, not of our domain.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name RawMessageDelivery --attribute-value true

echo "[init] audit fan-out ready: SNS pix-events → audit-queue (NO filter policy — all events, raw delivery) + audit-queue-dlq"

# ── S3 bucket: pix-audit-log (immutable) ──────────────────────────────────────
# --object-lock-enabled-for-bucket is a CREATE-TIME-ONLY flag in real AWS (there is no API to turn
# Object Lock on afterwards), which is why it cannot be moved into a converge step. It also turns
# versioning on implicitly — and FREEZES it: an explicit `put-bucket-versioning Status=Enabled`
# afterwards is rejected with `InvalidBucketState: An Object Lock configuration is present on this
# bucket, so the versioning state cannot be changed`, even though it asks for the state the bucket is
# already in. LocalStack reproduces that faithfully (it is how this comment got written). So there is
# deliberately no versioning call here: on a locked bucket, versioning is not configuration you
# converge, it is a property you inherit and can never suspend — which is exactly the guarantee we
# want, since suspending versioning would be the first move of anyone trying to erase the trail.
if awslocal s3api head-bucket --bucket pix-audit-log >/dev/null 2>&1; then
  echo "[init] bucket pix-audit-log already exists — skipping create"
else
  echo "[init] creating bucket pix-audit-log (object lock enabled at creation)"
  awslocal s3api create-bucket --bucket pix-audit-log --object-lock-enabled-for-bucket >/dev/null
fi

# 1825 days = 5 years, the BACEN retention. Applied as the bucket DEFAULT, so every PutObject inherits
# it without the writer asking — the audit writer (step 43) cannot forget to retain a line.
if awslocal s3api put-object-lock-configuration --bucket pix-audit-log \
     --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Days":1825}}}' >/dev/null 2>&1; then
  echo "[init] bucket ready: pix-audit-log (versioning ON, Object Lock COMPLIANCE, retention 1825d)"
else
  # Only reachable against a pre-existing bucket that was created WITHOUT the lock flag — a state real
  # AWS cannot repair either (the fix is to recreate the bucket). Warn loudly and keep going: aborting
  # ready.d under `set -e` would take the whole local stack down with it, and S3InitIT already fails
  # the build on exactly this.
  echo "[init] WARNING: could not apply Object Lock to pix-audit-log — the bucket predates this script" \
       "and was created without --object-lock-enabled-for-bucket. Recreate it (docker compose down -v)."
fi

# ── S3 bucket: pix-statement-archive (plain, rewritable) ──────────────────────
# Derived data, no lock, no versioning — see the header for why.
if awslocal s3api head-bucket --bucket pix-statement-archive >/dev/null 2>&1; then
  echo "[init] bucket pix-statement-archive already exists — skipping create"
else
  echo "[init] creating bucket pix-statement-archive (plain — derived, rewritable data)"
  awslocal s3api create-bucket --bucket pix-statement-archive >/dev/null
fi

# READINESS MARKER — the last line of the last script. LocalStackTestBase waits on it and the compose
# healthcheck asserts the last resource created above (pix-statement-archive). Move both if you append
# a script that sorts after this one.
echo "[init] audit storage ready: s3://pix-audit-log (immutable) + s3://pix-statement-archive (cold archive)"
