
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 23247 | 715.8 ms |
| ledger-service / none | 16990 | 536.9 ms |
| ledger-service / /internal/ledger/postings | 54008 | 33.6 ms |
| account-service / /internal/accounts/{id} | 54905 | 22.4 ms |
| account-service / none | 54905 | 22.4 ms |
| fraud-service / /internal/fraud/score | 54008 | 9.8 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / PutItem | 56334 | 10021.6 ms |
| DynamoDB / UpdateItem | 234013 | 1431.7 ms |
| DynamoDB / TransactWriteItems | 54008 | 1431.7 ms |
| DynamoDB / Query | 452 | 1431.7 ms |
| SNS / Publish | 18706 | 134.2 ms |
| redis / HELLO | 1 | 12.6 ms |
| redis / CLIENT | 2 | 2.8 ms |
| redis / GET | 32494 | 1.0 ms |
| redis / SETEX | 23247 | 1.0 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 56334 | 11453.2 ms |
| GET / /v1/accounts/me/balance | 32494 | 536.9 ms |
| GET / /v1/accounts/me/statement | 16990 | 536.9 ms |
| GET / /actuator/prometheus | 66 | 67.1 ms |
| GET / /actuator/health/** | 51 | 39.1 ms |
