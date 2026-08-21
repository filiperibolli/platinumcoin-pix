#!/bin/bash
# Step 26 — the messaging backbone: SNS topic `pix-events`, `settlement-queue` + its DLQ,
# and the SNS→SQS subscription filtered by eventType.
#
# This is the FIRST asynchronous infrastructure in the project (everything up to Sprint 5 was
# synchronous), so it is also where the naming convention is set:
#   topic  : pix-events                  — ONE topic, fan-out by subscription (ADR-0004)
#   queue  : <purpose>-queue             — one physical queue per CONSUMING SERVICE
#   dlq    : <purpose>-queue-dlq         — every queue has exactly one, same prefix
#
# Shape of the flow (ARCHITECTURE §6.6): payment-service drains its outbox to `pix-events`
# (step 29); SNS copies each message into every subscribed queue whose filter policy matches;
# settlement-service long-polls `settlement-queue` (step 31). SNS itself stores nothing — the
# durable copy is the one sitting in the queue (see docs/messaging-kafka-appendix.md for how
# that differs from a Kafka topic, which IS the log).
#
# LocalStack runs this once the emulator is ready (ready.d mount in ../../docker-compose.yml).
# Numbered 06 so it sorts after the DynamoDB tables and seeds. It was the readiness marker when it
# landed (step 26); steps 36 and 42 appended scripts after it, so the marker now lives on the final
# log line of 09-audit.sh — see LocalStackTestBase and docker-compose.yml.
#
# Idempotent: create-if-absent, then always converge the attributes (set-queue-attributes /
# set-subscription-attributes are idempotent, so a re-run also repairs drift instead of failing).
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

# ── SNS topic: pix-events ─────────────────────────────────────────────────────
# create-topic is idempotent by contract: the same name always returns the same ARN.
TOPIC_ARN=$(awslocal sns create-topic --name pix-events --query TopicArn --output text)
echo "[init] SNS topic ready: $TOPIC_ARN"

# ── settlement-queue-dlq (created FIRST — the main queue needs its ARN) ───────
# A message lands here after maxReceiveCount failed receives. It is NOT a lost message: it is a
# FLAGGED one (ADR-0003) — the reconciliation loop (step 35) and the DLQ-depth alert (step 44)
# own it from there. Retention is the SQS maximum, 14 days, so a flagged message survives a long
# weekend and stays available for a manual redrive.
DLQ_URL=$(create_queue_if_absent settlement-queue-dlq)
awslocal sqs set-queue-attributes --queue-url "$DLQ_URL" \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
DLQ_ARN=$(queue_arn "$DLQ_URL")
echo "[init] DLQ ready: $DLQ_ARN (retention 14d)"

# ── settlement-queue ─────────────────────────────────────────────────────────
# RedrivePolicy       — SQS moves the message to the DLQ by itself after 5 receives. This is
#                       native to SQS and is the whole reason a DLQ costs us nothing here; in
#                       Kafka the same behaviour is application code (messaging appendix).
# VisibilityTimeout   — 30s: must exceed the settlement consumer's SPI call (12s timeout, step
#                       31), otherwise SQS would redeliver a message that is still being worked
#                       on and two workers would race on the same transaction. It doubles as the
#                       retry backoff in step 32: a consumer that does NOT delete the message
#                       gets it back after this window.
# ReceiveMessageWaitTimeSeconds — 20s long polling: the consumer blocks on the queue instead of
#                       hammering it with empty receives (fewer requests, lower latency).
SETTLEMENT_URL=$(create_queue_if_absent settlement-queue)
awslocal sqs set-queue-attributes --queue-url "$SETTLEMENT_URL" --attributes "$(cat <<JSON
{
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"5\"}",
  "VisibilityTimeout": "30",
  "ReceiveMessageWaitTimeSeconds": "20"
}
JSON
)"
SETTLEMENT_ARN=$(queue_arn "$SETTLEMENT_URL")

# Let the topic write into the queue. The AWS console adds this policy for you when you wire a
# subscription by hand; the API does not — and a missing policy fails SILENTLY (SNS accepts the
# publish, delivery is denied, the message simply never arrives). Kept explicit and narrow:
# only this topic, only SendMessage.
awslocal sqs set-queue-attributes --queue-url "$SETTLEMENT_URL" --attributes "$(cat <<JSON
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"allow-pix-events-topic\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$SETTLEMENT_ARN\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$TOPIC_ARN\"}}}]}"
}
JSON
)"
echo "[init] queue ready: $SETTLEMENT_ARN (redrive → DLQ after 5 receives, visibility 30s, long-poll 20s)"

# ── subscription: pix-events → settlement-queue, filtered ────────────────────
# Guarded so a container restart does not pile up duplicate subscriptions (each duplicate would
# deliver another COPY of every event — at-least-once is fine, but this would be self-inflicted).
SUBSCRIPTION_ARN=$(awslocal sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" \
  --query "Subscriptions[?Endpoint=='$SETTLEMENT_ARN'].SubscriptionArn | [0]" --output text)
if [ "$SUBSCRIPTION_ARN" = "None" ] || [ -z "$SUBSCRIPTION_ARN" ]; then
  echo "[init] subscribing settlement-queue to pix-events"
  SUBSCRIPTION_ARN=$(awslocal sns subscribe --topic-arn "$TOPIC_ARN" \
    --protocol sqs --notification-endpoint "$SETTLEMENT_ARN" \
    --query SubscriptionArn --output text)
else
  echo "[init] subscription already exists — converging its attributes"
fi

# FilterPolicy — broker-side routing on the `eventType` message attribute the outbox publisher
# sets (step 29). settlement only cares about PixDebited; every other event type is dropped by
# SNS and never costs the consumer a receive. Later sprints ADD their own queue + policy
# (notification-queue: PixSettled/PixReceived/PixReversed, step 36; audit-queue: unfiltered,
# step 42) — the topic never changes, only its subscriptions.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy --attribute-value '{"eventType":["PixDebited"]}'

# RawMessageDelivery — deliver the event JSON as published instead of wrapping it in the SNS
# notification envelope ({"Type":"Notification","Message":"<escaped json>",...}). The consumer
# then parses the SAME envelope the publisher wrote, with no SNS-specific unwrapping step —
# which is exactly what keeps the consumer broker-agnostic (messaging appendix). Message
# attributes are preserved as SQS message attributes, so filtering is unaffected.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name RawMessageDelivery --attribute-value true

echo "[init] messaging ready: SNS pix-events → settlement-queue (filter eventType=PixDebited, raw delivery) + settlement-queue-dlq"
