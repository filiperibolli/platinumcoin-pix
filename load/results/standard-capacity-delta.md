# Operations performed by the `standard` profile

Accepted sends: **18692**. "per send" divides by that.


## payment-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/UpdateItem | 94,179 | 5.04 |
| DynamoDB/PutItem | 20,402 | 1.09 |
| SNS/Publish | 18,773 | 1.00 |
| DynamoDB/TransactWriteItems | 18,768 | 1.00 |
| redis/GET | 6,838 | 0.37 |
| redis/SETEX | 6,294 | 0.34 |
| DynamoDB/Query | 1,572 | 0.08 |
| redis/CLIENT | 2 | 0.00 |
| redis/HELLO | 1 | 0.00 |

## ledger-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/TransactWriteItems | 18,768 | 1.00 |
| redis/DEL | 18,768 | 1.00 |
| DynamoDB/GetItem | 6,294 | 0.34 |
| DynamoDB/Query | 4,668 | 0.25 |
| DynamoDB/Scan | 276 | 0.01 |

## account-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/GetItem | 19,204 | 1.03 |
| DynamoDB/Query | 19,204 | 1.03 |

## fraud-service

| dependency / operation | calls | per send |
|---|---:|---:|
| redis/INCR | 18,768 | 1.00 |
| redis/INCRBY | 18,768 | 1.00 |
| redis/SADD | 18,768 | 1.00 |
| redis/PEXPIRE | 1,723 | 0.09 |
| redis/CLIENT | 2 | 0.00 |
| redis/HELLO | 1 | 0.00 |

## settlement-service

| dependency / operation | calls | per send |
|---|---:|---:|
| SQS/ReceiveMessage | 58 | 0.00 |
| DynamoDB/Query | 36 | 0.00 |
| SQS/DeleteMessageBatch | 31 | 0.00 |
| SQS/ChangeMessageVisibilityBatch | 29 | 0.00 |
| SQS/GetQueueAttributes | 29 | 0.00 |

**Total dependency calls across all five services: 312,224** = 16.70 per send.
