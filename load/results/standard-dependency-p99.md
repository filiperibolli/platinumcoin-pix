
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 6294 | 1431.7 ms |
| ledger-service / none | 3420 | 1431.7 ms |
| account-service / /internal/accounts/{id} | 19204 | 805.3 ms |
| account-service / none | 19204 | 805.3 ms |
| ledger-service / /internal/ledger/postings | 18768 | 447.4 ms |
| fraud-service / /internal/fraud/score | 18768 | 9.8 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / PutItem | 20402 | 17179.9 ms |
| DynamoDB / UpdateItem | 94179 | 1789.6 ms |
| DynamoDB / TransactWriteItems | 18768 | 984.3 ms |
| DynamoDB / Query | 1588 | 134.2 ms |
| SNS / Publish | 18773 | 61.5 ms |
| redis / HELLO | 1 | 39.1 ms |
| redis / CLIENT | 2 | 7.0 ms |
| redis / SETEX | 6294 | 1.7 ms |
| redis / GET | 6838 | 1.0 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 20402 | 28633.1 ms |
| GET / /v1/accounts/me/balance | 6838 | 1431.7 ms |
| GET / /v1/accounts/me/statement | 3420 | 1431.7 ms |
| GET / /actuator/health/** | 55 | 67.1 ms |
| GET / /actuator/prometheus | 62 | 33.6 ms |
