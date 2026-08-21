#!/bin/bash
# Step 36 — the SECOND consumer off pix-events: notification-queue + its DLQ, subscribed to the
# topic and filtered to the user-facing outcomes only.
#
# This is fan-out made concrete (ADR-0004). The topic pix-events does not change; step 26 hung
# settlement-queue off it (filter eventType=PixDebited), and this step hangs a second, independent
# physical queue off the SAME topic with its OWN filter policy — the SNS+SQS analogue of a second
# Kafka consumer group (docs/messaging-kafka-appendix.md). settlement wakes on the INTERNAL
# PixDebited; notification wakes only on what a HUMAN should see:
#   PixSettled  — the sender's external Pix reached SETTLED
#   PixReceived — an inbound Pix credited this user (step 37)
#   PixReversed — a failed external Pix was compensated back (step 33)
# The two policies are deliberately disjoint: an internal PixDebited must never wake a notification,
# and a user-facing outcome must never hit the settlement consumer.
#
# WHY NO inbound-pix-queue HERE: an earlier draft added one, but step 37 processes the BACEN inbound
# webhook synchronously and idempotently inside settlement-service — a queue with no consumer is
# worse than no queue (it hides backpressure and rots). We only create infrastructure something
# actually drains (the discipline of vertical delivery).
#
# Numbered 08 so it sorts after the messaging core. Its final log line was the readiness marker for
# the Testcontainers harness and the compose healthcheck between steps 36 and 42; step 42 appended
# 09-audit.sh after it and moved both markers there (LocalStackTestBase, docker-compose.yml).
#
# Idempotent: create-if-absent, then always converge attributes (set-queue-attributes /
# set-subscription-attributes are idempotent, so a re-run also repairs drift). Mirrors the shape of
# 06-messaging-core.sh on purpose — same helpers, same guards.
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

# ── notification-queue-dlq (created FIRST — the main queue needs its ARN) ──────
# Same flagged-not-lost posture as settlement's DLQ (ADR-0003): a message lands here after
# maxReceiveCount failed receives, and stays for the SQS maximum of 14 days.
NOTIFY_DLQ_URL=$(create_queue_if_absent notification-queue-dlq)
awslocal sqs set-queue-attributes --queue-url "$NOTIFY_DLQ_URL" \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
NOTIFY_DLQ_ARN=$(queue_arn "$NOTIFY_DLQ_URL")
echo "[init] DLQ ready: $NOTIFY_DLQ_ARN (retention 14d)"

# ── notification-queue ────────────────────────────────────────────────────────
# Tuned the same as settlement-queue for consistency. The notification consumer (step 38) only
# dedupes by eventId then pushes to an in-memory SSE registry — sub-second, no long external call
# like settlement's 12s SPI — so 30s is generous headroom rather than a hard floor; 20s long polling
# keeps the consumer blocked on the queue instead of hammering it; redrive to the DLQ after 5
# receives makes a poison message a flagged one, not an infinite retry loop.
NOTIFY_URL=$(create_queue_if_absent notification-queue)
awslocal sqs set-queue-attributes --queue-url "$NOTIFY_URL" --attributes "$(cat <<JSON
{
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$NOTIFY_DLQ_ARN\",\"maxReceiveCount\":\"5\"}",
  "VisibilityTimeout": "30",
  "ReceiveMessageWaitTimeSeconds": "20"
}
JSON
)"
NOTIFY_ARN=$(queue_arn "$NOTIFY_URL")

# Let the topic write into the queue — narrow policy, only this topic, only SendMessage. The API
# (unlike the console) does not add this for you, and a missing policy fails SILENTLY (publish
# accepted, delivery denied, the message simply never arrives).
awslocal sqs set-queue-attributes --queue-url "$NOTIFY_URL" --attributes "$(cat <<JSON
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"allow-pix-events-topic\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$NOTIFY_ARN\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$TOPIC_ARN\"}}}]}"
}
JSON
)"
echo "[init] queue ready: $NOTIFY_ARN (redrive → DLQ after 5 receives, visibility 30s, long-poll 20s)"

# ── subscription: pix-events → notification-queue, filtered ───────────────────
# Guarded so a container restart does not pile up duplicate subscriptions (each duplicate would
# deliver another COPY of every matching event).
SUBSCRIPTION_ARN=$(awslocal sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" \
  --query "Subscriptions[?Endpoint=='$NOTIFY_ARN'].SubscriptionArn | [0]" --output text)
if [ "$SUBSCRIPTION_ARN" = "None" ] || [ -z "$SUBSCRIPTION_ARN" ]; then
  echo "[init] subscribing notification-queue to pix-events"
  SUBSCRIPTION_ARN=$(awslocal sns subscribe --topic-arn "$TOPIC_ARN" \
    --protocol sqs --notification-endpoint "$NOTIFY_ARN" \
    --query SubscriptionArn --output text)
else
  echo "[init] subscription already exists — converging its attributes"
fi

# FilterPolicy — broker-side routing on the `eventType` message attribute the outbox publisher sets
# (step 29). notification only receives the user-facing outcomes; every other event type (PixDebited,
# FraudCheckSkipped, …) is dropped by SNS and never costs the consumer a receive.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy \
  --attribute-value '{"eventType":["PixSettled","PixReceived","PixReversed"]}'

# RawMessageDelivery — deliver the event JSON as published, not wrapped in the SNS notification
# envelope, so the consumer parses the SAME envelope the publisher wrote (broker-agnostic; messaging
# appendix). Message attributes are preserved as SQS message attributes, so filtering is unaffected.
awslocal sns set-subscription-attributes --subscription-arn "$SUBSCRIPTION_ARN" \
  --attribute-name RawMessageDelivery --attribute-value true

echo "[init] notify messaging ready: SNS pix-events → notification-queue (filter eventType=PixSettled,PixReceived,PixReversed, raw delivery) + notification-queue-dlq"
