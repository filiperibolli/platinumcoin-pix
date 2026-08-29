#!/bin/bash
# Step 53 — the cold statement export's infrastructure: statement-export-queue (+DLQ), subscribed to
# pix-events and filtered to StatementExportRequested, plus the bucket the CSV artifacts land in.
#
# This is the FOURTH physical queue hung off the same topic (settlement, notification, audit, export)
# and the pattern has not changed once: one topic, N independent subscriptions, each with its own
# filter policy — the SNS+SQS analogue of N Kafka consumer groups
# (docs/messaging-kafka-appendix.md). What is new is who consumes: for the first time the consumer is
# payment-service, the service that also publishes. That is not a loop — it publishes a request and
# consumes it back as work to do, which is exactly what an async request resource is.
#
# WHY maxReceiveCount IS 5 WHILE THE WORKER GIVES UP AT 3: the two budgets answer different
# questions and the ordering between them is deliberate. The worker's budget (pix.export.max-attempts)
# decides when a customer gets an answer — a FAILED export with a reason they can read. The queue's
# redrive decides when an operator gets one. Keeping the worker's number lower means an ordinary
# failing export NEVER reaches the DLQ; what reaches it is only what the worker could not even parse
# or resolve, i.e. a defect. That is what makes "DLQ depth > 0" a signal worth alerting on here.
#
# Numbered 10 so it sorts after the audit script — WHICH MOVES THE READINESS MARKER. LocalStack runs
# ready.d in lexical order and both the Testcontainers harness (LocalStackTestBase) and the compose
# healthcheck wait on the LAST script's final log line; step 42 moved that marker from
# 08-messaging-notify.sh to 09-audit.sh, and this step moves it here. Appending an 11-*.sh moves it
# again — if you forget, every integration test in the repo hangs for two minutes and then fails with
# a startup timeout that says nothing about why.
#
# Idempotent: create-if-absent, then always converge attributes (set-queue-attributes /
# set-subscription-attributes / create-bucket guards are idempotent, so a re-run also repairs drift).
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://localhost:4566"

awslocal() { aws --endpoint-url="$ENDPOINT" "$@"; }

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

# ── statement-export-queue-dlq (created FIRST — the main queue needs its ARN) ──
EXPORT_DLQ_URL=$(create_queue_if_absent statement-export-queue-dlq)
awslocal sqs set-queue-attributes --queue-url "$EXPORT_DLQ_URL" \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
EXPORT_DLQ_ARN=$(queue_arn "$EXPORT_DLQ_URL")
echo "[init] DLQ ready: $EXPORT_DLQ_ARN (retention 14d)"

# ── statement-export-queue ────────────────────────────────────────────────────
# VisibilityTimeout 120s, four times the other queues'. Assembling an export is the one piece of work
# in this platform that is legitimately SLOW: up to 24 archive objects read, merged and uploaded. At
# 30s a large export would still be running when SQS handed the same message to a second worker —
# which is safe (the guarded PENDING→READY transition and the fixed object key see to that) but
# wasteful, and it would burn the attempt budget on deliveries that never failed. Long polling stays
# at 20s: waiting cheaply is unrelated to working slowly.
EXPORT_URL=$(create_queue_if_absent statement-export-queue)
awslocal sqs set-queue-attributes --queue-url "$EXPORT_URL" --attributes "$(cat <<JSON
{
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$EXPORT_DLQ_ARN\",\"maxReceiveCount\":\"5\"}",
  "VisibilityTimeout": "120",
  "ReceiveMessageWaitTimeSeconds": "20"
}
JSON
)"
EXPORT_ARN=$(queue_arn "$EXPORT_URL")

# Let the topic write into the queue — narrow policy, only this topic, only SendMessage. A missing
# policy fails SILENTLY (publish accepted, delivery denied, the message simply never arrives).
awslocal sqs set-queue-attributes --queue-url "$EXPORT_URL" --attributes "$(cat <<JSON
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"allow-pix-events-topic\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$EXPORT_ARN\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$TOPIC_ARN\"}}}]}"
}
JSON
)"
echo "[init] queue ready: $EXPORT_ARN (redrive → DLQ after 5 receives, visibility 120s, long-poll 20s)"

# ── subscription: pix-events → statement-export-queue, filtered ───────────────
# Guarded so a container restart does not pile up duplicate subscriptions (each duplicate would
# deliver another COPY of every matching event).
SUBSCRIPTION_ARN=$(awslocal sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" \
  --query "Subscriptions[?Endpoint=='$EXPORT_ARN'].SubscriptionArn | [0]" --output text)
if [ "$SUBSCRIPTION_ARN" = "None" ] || [ -z "$SUBSCRIPTION_ARN" ]; then
  echo "[init] subscribing statement-export-queue to pix-events"
  SUBSCRIPTION_ARN=$(awslocal sns subscribe --topic-arn "$TOPIC_ARN" \
    --protocol sqs --notification-endpoint "$EXPORT_ARN" \
    --query SubscriptionArn --output text)
else
  echo "[init] subscription already exists — converging its attributes"
fi

# FilterPolicy — exactly one event type. The export worker must never be woken by a payment event:
# it would cost a receive, a parse and a rejection for every Pix the platform processes.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy \
  --attribute-value '{"eventType":["StatementExportRequested"]}'

# RawMessageDelivery — deliver the event JSON as published, not wrapped in the SNS envelope, so the
# consumer parses the SAME envelope the publisher wrote (broker-agnostic; messaging appendix).
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name RawMessageDelivery --attribute-value true

# ── S3 bucket: pix-statement-exports (plain, rewritable) ──────────────────────
# A PLAIN bucket, like pix-statement-archive and unlike pix-audit-log: an export artifact is DERIVED
# data twice over — a rendering of the archive, which is itself a projection of the ledger. It can be
# regenerated from the source of truth at any time, and the worker rewrites the same key whole on a
# retry, which on a version-locked bucket would pile up undeletable copies of a regenerable file.
#
# WHAT A REAL DEPLOYMENT ADDS HERE, and why it is out of scope locally: a lifecycle rule expiring
# objects after ~30 days. An export is a convenience copy a customer downloads once, so keeping it for
# five years would be paying audit-grade storage for a cache. LocalStack accepts the lifecycle API but
# never runs the expiration, so configuring it here would look like a guarantee the sandbox does not
# provide — it is documented instead (docs/data-model.md, ARCHITECTURE §6.14).
if awslocal s3api head-bucket --bucket pix-statement-exports >/dev/null 2>&1; then
  echo "[init] bucket pix-statement-exports already exists — skipping create"
else
  echo "[init] creating bucket pix-statement-exports (plain — derived, rewritable artifacts)"
  awslocal s3api create-bucket --bucket pix-statement-exports >/dev/null
fi

# THE READINESS MARKER (see the header). LocalStackTestBase and the compose healthcheck wait on this
# exact line; moving or rewording it without updating both is how every IT in the repo starts failing
# with a two-minute startup timeout.
echo "[init] statement export ready: SNS pix-events → statement-export-queue (filter eventType=StatementExportRequested, raw delivery) + statement-export-queue-dlq + s3://pix-statement-exports"
