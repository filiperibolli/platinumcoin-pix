
Service hops (http_client_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| ledger-service / /internal/ledger/accounts/{accountId}/balance | 15796 | 3221.2 ms |
| ledger-service / none | 12817 | 3221.2 ms |
| account-service / /internal/accounts/{id} | 17442 | 1789.6 ms |
| account-service / none | 17442 | 1789.6 ms |
| ledger-service / /internal/ledger/postings | 16511 | 55.9 ms |
| fraud-service / /internal/fraud/score | 16498 | 11.2 ms |

Infrastructure hops (pix_dependency_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| DynamoDB / PutItem | 26098 | 22906.5 ms |
| DynamoDB / Query | 112 | 10021.6 ms |
| DynamoDB / TransactWriteItems | 16492 | 5726.6 ms |
| DynamoDB / UpdateItem | 68492 | 4295.0 ms |
| SNS / Publish | 2288 | 44.7 ms |
| redis / HELLO | 1 | 8.4 ms |
| redis / SETEX | 14420 | 2.8 ms |
| redis / CLIENT | 2 | 2.4 ms |
| redis / GET | 19434 | 1.7 ms |

For comparison — this service's own endpoints (http_server_requests_seconds)
| dependency | calls | p99 (upper bound) |
|---|---:|---:|
| POST / /v1/payments/pix | 26098 | 14316.6 ms |
| GET / /v1/accounts/me/balance | 19434 | 3221.2 ms |
| GET / /v1/accounts/me/statement | 12817 | 3221.2 ms |
| GET / /actuator/health/** | 46 | 111.8 ms |
| GET / /actuator/prometheus | 67 | 15.4 ms |
