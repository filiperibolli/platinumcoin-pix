# Operations performed by the `black-friday` profile

Accepted sends: **15717**. "per send" divides by that.


## payment-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/UpdateItem | 68,492 | 4.36 |
| DynamoDB/PutItem | 26,098 | 1.66 |
| redis/GET | 19,434 | 1.24 |
| DynamoDB/TransactWriteItems | 16,492 | 1.05 |
| redis/SETEX | 14,420 | 0.92 |
| SNS/Publish | 2,288 | 0.15 |
| DynamoDB/Query | 111 | 0.01 |
| redis/CLIENT | 2 | 0.00 |
| redis/HELLO | 1 | 0.00 |

## ledger-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/TransactWriteItems | 16,511 | 1.05 |
| redis/DEL | 16,511 | 1.05 |
| DynamoDB/GetItem | 15,796 | 1.01 |
| DynamoDB/Query | 13,291 | 0.85 |
| DynamoDB/Scan | 297 | 0.02 |

## account-service

| dependency / operation | calls | per send |
|---|---:|---:|
| DynamoDB/GetItem | 17,442 | 1.11 |
| DynamoDB/Query | 17,442 | 1.11 |

## fraud-service

| dependency / operation | calls | per send |
|---|---:|---:|
| redis/INCR | 16,498 | 1.05 |
| redis/INCRBY | 16,498 | 1.05 |
| redis/SADD | 16,498 | 1.05 |
| redis/PEXPIRE | 1,026 | 0.07 |
| redis/CLIENT | 2 | 0.00 |
| redis/HELLO | 1 | 0.00 |

## settlement-service

| dependency / operation | calls | per send |
|---|---:|---:|
| SQS/ReceiveMessage | 60 | 0.00 |
| DynamoDB/Query | 40 | 0.00 |
| SQS/ChangeMessageVisibilityBatch | 30 | 0.00 |
| SQS/DeleteMessageBatch | 30 | 0.00 |
| SQS/GetQueueAttributes | 30 | 0.00 |

**Total dependency calls across all five services: 295,341** = 18.79 per send.
