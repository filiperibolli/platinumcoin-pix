
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 15929 | 2863.3 ms |
| ledger-service / none | 13296 | 2863.3 ms |
| account-service / /internal/accounts/{id} | 18661 | 1789.6 ms |
| account-service / none | 18661 | 1789.6 ms |
| ledger-service / /internal/ledger/postings | 17695 | 44.7 ms |
| fraud-service / /internal/fraud/score | 17687 | 11.2 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / PutItem | 26521 | 22906.5 ms |
| DynamoDB / Query | 86 | 14316.6 ms |
| DynamoDB / TransactWriteItems | 17684 | 5726.6 ms |
| DynamoDB / UpdateItem | 75879 | 3579.1 ms |
| SNS / Publish | 4890 | 89.5 ms |
| redis / HELLO | 1 | 8.4 ms |
| redis / SETEX | 15193 | 3.5 ms |
| redis / CLIENT | 2 | 3.5 ms |
| redis / GET | 20603 | 2.1 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 26521 | 12884.9 ms |
| GET / /v1/accounts/me/balance | 20603 | 2863.3 ms |
| GET / /v1/accounts/me/statement | 13296 | 2863.3 ms |
| GET / /actuator/health/** | 44 | 33.6 ms |
| GET / /actuator/prometheus | 65 | 22.4 ms |
