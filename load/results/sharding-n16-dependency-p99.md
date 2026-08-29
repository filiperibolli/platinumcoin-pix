
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 23601 | 715.8 ms |
| ledger-service / none | 16998 | 626.3 ms |
| ledger-service / /internal/ledger/postings | 56003 | 33.6 ms |
| account-service / /internal/accounts/{id} | 56975 | 22.4 ms |
| account-service / none | 56975 | 22.4 ms |
| fraud-service / /internal/fraud/score | 56003 | 9.8 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / PutItem | 58488 | 11453.2 ms |
| DynamoDB / UpdateItem | 243848 | 1431.7 ms |
| DynamoDB / TransactWriteItems | 56003 | 1431.7 ms |
| DynamoDB / Query | 480 | 1431.7 ms |
| SNS / Publish | 20338 | 134.2 ms |
| redis / HELLO | 1 | 12.6 ms |
| redis / CLIENT | 2 | 5.6 ms |
| redis / GET | 32460 | 1.0 ms |
| redis / SETEX | 23601 | 1.0 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 58488 | 11453.2 ms |
| GET / /v1/accounts/me/balance | 32460 | 626.3 ms |
| GET / /v1/accounts/me/statement | 16998 | 626.3 ms |
| GET / /actuator/health/** | 52 | 67.1 ms |
| GET / /actuator/prometheus | 67 | 61.5 ms |
