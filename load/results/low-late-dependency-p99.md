
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| account-service / /internal/accounts/{id} | 150 | 3221.2 ms |
| account-service / none | 150 | 3221.2 ms |
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 95 | 3221.2 ms |
| ledger-service / none | 91 | 3221.2 ms |
| ledger-service / /internal/ledger/postings | 142 | 2863.3 ms |
| fraud-service / /internal/fraud/score | 134 | 16.8 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / UpdateItem | 695 | > last bucket |
| DynamoDB / PutItem | 321 | > last bucket |
| DynamoDB / TransactWriteItems | 129 | > last bucket |
| DynamoDB / Query | 9 | 3221.2 ms |
| SNS / Publish | 204 | 44.7 ms |
| redis / HELLO | 1 | 8.4 ms |
| redis / GET | 181 | 5.6 ms |
| redis / CLIENT | 2 | 3.1 ms |
| redis / SETEX | 95 | 1.4 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 321 | 11453.2 ms |
| GET / /v1/accounts/me/statement | 91 | 3221.2 ms |
| GET / /v1/accounts/me/balance | 181 | 2863.3 ms |
| GET / /actuator/health/** | 20 | 61.5 ms |
| GET / /actuator/prometheus | 21 | 28.0 ms |
