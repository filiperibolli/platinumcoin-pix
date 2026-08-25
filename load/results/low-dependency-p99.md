
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / none | 91 | 39.1 ms |
| fraud-service / /internal/fraud/score | 630 | 11.2 ms |
| ledger-service / /internal/ledger/postings | 630 | 11.2 ms |
| account-service / /internal/accounts/{id} | 630 | 7.0 ms |
| account-service / none | 630 | 7.0 ms |
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 159 | 5.6 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| SNS / Publish | 632 | 39.1 ms |
| redis / HELLO | 1 | 7.0 ms |
| DynamoDB / TransactWriteItems | 630 | 5.6 ms |
| DynamoDB / Query | 1110 | 3.5 ms |
| DynamoDB / UpdateItem | 3152 | 3.1 ms |
| DynamoDB / PutItem | 630 | 2.8 ms |
| redis / CLIENT | 2 | 2.1 ms |
| redis / GET | 180 | 1.4 ms |
| redis / SETEX | 159 | 1.0 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| GET / /v1/accounts/me/statement | 91 | 134.2 ms |
| GET / /actuator/prometheus | 18 | 67.1 ms |
| GET / /actuator/health/** | 19 | 61.5 ms |
| POST / /v1/payments/pix | 630 | 55.9 ms |
| GET / /v1/accounts/me/balance | 180 | 11.2 ms |
