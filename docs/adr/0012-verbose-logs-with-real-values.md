# ADR-0012: Verbose, descriptive logs with real values — a sandbox posture (LGPD)

**Status:** Accepted · **Date:** 2026-08-01 · **Scope:** every service, `common-lib`'s
`logback-spring.xml`, and the logging convention in `CLAUDE.md`

> **Read this first if you are evaluating the code.** This platform is a **local sandbox** — no real
> customers, no real CPFs, no real money, no production deployment, no network exposure beyond
> `localhost` and `docker compose`. Every identifier in it is a seeded fixture (`alice`, `acc-001`,
> `u-alice`). The logging posture decided here — **print the values, in full, in the clear** — is
> chosen *because* of that, is stated as a deliberate trade-off, and is **not** what this same code
> would do with production data. §"What changes in production" is the operative part of the ADR.

## Context

Three separate problems with the logging as it stood after step 11:

**1. A filter was logging so that the correlation id would be visible somewhere.**
`CorrelationIdFilter` emitted one `INFO http.request method=… path=… status=… durationMs=…` per call.
That line existed largely to prove "a request happened, here is its id" — but the id belongs to
*every* record, not to one of them. Meanwhile the line had to special-case `/actuator` so that
healthcheck probes every 10s would not drown the real traffic: a smell that the mechanism was wrong.

**2. The default format was machine-first.** JSON (logstash encoder) was the default and a
human-readable console pattern lived behind the `dev` profile — which nothing ever activated, so
`docker compose logs` printed JSON that a human reads by piping through `jq`. For a build whose
stated purpose is that a reader can *follow* a Pix payment across services, the default was backwards.

**3. Messages were addressed to a grep, not to a reader.** Two conventions collided:

- Event names were dotted, low-cardinality tokens: `account.key.resolve.miss`,
  `auth.login.denied`, `account.key.repo.putItem.conflict`. You had to already know the codebase to
  know what `account.me.missing` meant, and nothing on the line said what the service *did about it*.
- Values were withheld on exactly the lines where the value is the question. `ResolvePixKeyUseCase`
  logged `account.key.resolve.request` — no key — and then `account.key.resolve.miss` — still no key.
  A DICT lookup trace that hides the key it looked up cannot answer the only question ever asked of
  it: *why did **this** key not resolve?* The withholding was PII prudence applied to seeded fixtures.

The underlying tension is real and worth naming: **logs are the primary debugging surface of a
distributed system, and they are also a data store that outlives the request and is copied around**
(container logs, CI artifacts, screenshots in a README, a log platform). LGPD (Lei 13.709/2018)
treats a CPF, a phone number, an e-mail and a Pix key as *dados pessoais*; a payment amount tied to
an account is *dado pessoal* too. Production logging must therefore minimize (art. 6º, III), and the
usual mechanics are masking, tokenization or hashing at the log boundary.

## Decision

**In this sandbox, logs are optimized for a human reading them, and they carry the real values.**
Concretely, five rules — all enforced from `common-lib` so a new service inherits them by depending
on it, and all restated in `CLAUDE.md` as conventions every future step follows:

### 1. The correlation id is in the *pattern*, on every record — no filter logs it

`common-lib/src/main/resources/logback-spring.xml` sets Spring Boot's own
`LOG_CORRELATION_PATTERN` hook (the one behind `logging.pattern.correlation`) to
`[cid=%X{correlationId:-n/a} tx=%X{txId:-n/a}] `, so **every** line — ours, Spring's, Tomcat's, the
AWS SDK's — is prefixed with the ids:

```
2026-08-01T10:39:28.962-03:00  INFO 13929 --- [auth-service] [nio-8081-exec-1] \
  [cid=abbb4c1c-81aa-4aaa-808c-b508ba11fec2 tx=n/a] c.p.p.auth.domain.usecase.LoginUseCase \
  : Login succeeded, access token issued | username=alice userId=u-alice accountId=acc-001 expiresInSeconds=900
```

`CorrelationIdFilter` keeps only its real job (read-or-generate the id, put it on the MDC, echo the
header) and **logs nothing**. `grep <cid>` over `docker compose logs` now returns the *whole* path of
a request, framework lines included — strictly more than the one line it replaced. Threads with no
request (startup, schedulers) print `n/a`, which is itself information.

### 2. Human-readable console is the default; JSON is one profile away

Default output is Spring Boot's console pattern (above). The logstash JSON encoder stays configured
and is enabled with `SPRING_PROFILES_ACTIVE=json-logs`, which is what a deployment shipping to a log
platform would use (`jq 'select(.correlationId=="…")'` still works there). The `dev` profile no
longer affects logging — the readable format is not a special mode.

### 3. Messages are English sentences that say what happened and what the service did

The dotted-token convention (`<domain>.<action>.<outcome>`) is **replaced**. A log line is now:

```
<English sentence, past tense, naming the decision and its consequence> | key=value key=value …
```

- Prose first, because that is what a human reads: *"Pix-key deletion refused, the key belongs to
  another account, returning 403"*, not `account.key.delete.forbidden`.
- Structured `key=value` pairs after a ` | `, because that is what a grep reads. Same information,
  both audiences, one line.
- Where a decision has a non-obvious reason, the sentence carries it — *"strongly consistent because
  both key parts come from the JWT"*, *"the client is only told 'invalid credentials'"*. The log is
  part of how this codebase explains itself.

### 4. Values are logged in full, including personal data — this is the sandbox trade-off

Pix keys (CPF, phone, e-mail, EVP), account ids, user ids, usernames, daily limits, timestamps, the
DynamoDB partition keys actually read and written, and the fields of a rejected request body are all
printed verbatim. A raw value and its normalized form are logged **side by side** where
normalization is a rule (`rawValue=Alice@Mail.com storedValue=alice@mail.com`), because that is what
turns "my key was rejected" from a debugging session into a glance.

**The line that is not crossed: secrets are never logged.** Not the password, not the bcrypt hash,
not the minted JWT, not AWS credentials. A personal datum in a sandbox log is a documented,
bounded trade-off; a credential in any log is a vulnerability. `JwtIssuer` logs the *claims* it
signed (`jti`, `sub`, `accountId`, `iat`, `exp`) and never the compact token.

### 5. `com.platinumcoin.pix` logs at DEBUG by default; everything else at INFO

Adapter detail — the exact `GetItem`/`Query`/`PutItem` and its keys, the item as read — is visible
without knowing a flag exists. The contract from the previous convention still holds and is the
reason DEBUG is *additive*: **the INFO layer alone must already tell the full story of a call**;
DEBUG only adds the *how*. Framework packages stay at INFO so the domain is not drowned.

## Consequences

**Gained**

- One `grep <correlationId>` reconstructs a request end to end, including framework lines — the
  KR4.1 claim in the README is now a property of the pattern, not of a filter someone must remember
  to keep.
- The logs read as an explanation of the system. Onboarding, demoing and debugging use the same
  artifact, which is the point of a portfolio build.
- Healthcheck noise is gone with the per-request line; the actuator special-case disappeared with it.
- No per-service logging wiring: a new service gets all five rules by depending on `common-lib`.

**Given up / accepted**

- **No per-request summary line.** Nothing now prints `method path status durationMs` for a
  successful call. Latency and status codes are a *metrics* question (Micrometer + Prometheus, step
  44) and that is where they will be answered; per-call HTTP detail at INFO would be a metric
  rendered as a log. Until then, an authenticated call is visible through
  `JwtAuthFilter`'s DEBUG line (`method`/`path`/`userId`) and its business-stage INFO events, and a
  *failed* call is visible at WARN/ERROR with its status (`JwtAuthFilter` 401, `GlobalExceptionHandler`
  4xx/5xx). If step 44 shows this gap hurts, the fix is an explicit access-log decision, not a
  filter that logs because the id needed a home.
- **Log volume.** DEBUG-by-default plus values makes a call print several lines instead of one. At
  sandbox traffic this is free; the k6 load profiles (step 47) run with
  `logging.level.com.platinumcoin.pix=INFO` so log I/O does not distort the latency numbers being
  measured.
- **Verbosity in messages** costs a little screen width and makes log-message assertions in tests
  brittle — so tests assert on behaviour and HTTP contracts, never on log text.
- **The logs are now full of personal-shaped data.** In this repository that data is fictional, and
  the container logs are ephemeral and local. It still means: do not point this compose stack at real
  data, and do not paste raw logs from a fork that did.

## What changes in production (the part that is not optional)

This ADR is a sandbox posture. A deployment handling real customers keeps rules 1–3 (they cost
nothing and are pure clarity) and **reverses rules 4 and 5**:

| Sandbox (here) | Production |
| --- | --- |
| Pix key, CPF, e-mail, phone printed in full | Masked or tokenized at the log boundary (`***@mail.com`, `123.***.***-45`) or replaced by a surrogate id; the raw value only in the record store, under access control |
| Account/user ids in the clear | Kept — they are internal surrogates, not personal data on their own, and they are what makes a trace joinable |
| Rejected request bodies printed verbatim | Field *names* only, values masked |
| `com.platinumcoin.pix` at DEBUG by default | INFO by default, DEBUG enabled per-logger, temporarily, for an incident |
| Console text | JSON (`json-logs`) to a log platform with retention limits and access control |
| Logs are ephemeral and local | Retention policy, access log of the log platform, and the LGPD rights that follow (art. 18) — including the fact that a personal datum inside a log is subject to deletion requests, which is the single strongest practical argument for not putting it there |

The mechanism for that reversal is deliberately cheap and is already where it belongs: masking is a
property of the *edge* where a value enters a log message, and the logging config is one file in
`common-lib`. The immutable audit trail (§7.6, S3 + Object Lock) — not the application log — is the
system of record for what happened to a payment; logs are a debugging aid with a short life.

## Alternatives considered

- **Keep the `http.request` line and add the pattern.** Redundant: the line's value was the id, and
  the id is now everywhere. The actuator special-case would have survived for no reason.
- **Mask personal values here too.** Rejected for this build: masking a fixture buys no privacy and
  costs the exact information the trace exists to show — while the *appearance* of a
  production-grade posture would be more misleading than the honest sandbox statement above. The
  production behaviour is documented instead of imitated.
- **Keep dotted event names alongside prose** (`… | event=account.key.resolve.miss`). Rejected as
  two names for one thing that will drift apart. Stable machine-readable stage counters are a
  metrics concern (step 44), where they are typed, not string-matched.
- **Structured JSON as the default, read through `jq`.** That is the production answer and it stays
  one profile away; making it the default optimizes for a consumer this build does not have.
