# TESTING.md — Testing Strategy

## 1. Testing Philosophy

For a billing system, tests exist primarily to prove **money math is correct** and **duplicate/
concurrent/out-of-order events cannot corrupt state**. Coverage percentage is a secondary signal;
the primary signal is: does the test suite catch a regression in proration rounding, a double-charge
from a replayed webhook, or a race condition in idempotency key handling? Tests are organized in the
classic pyramid (many unit tests, fewer integration tests, a handful of true end-to-end tests) but
with an explicit **fourth category — concurrency tests — elevated to first-class status**, because
that is where billing systems most often fail in production and it is the hardest category to get
right by accident.

## 2. Unit Testing Strategy

**Scope**: Pure business logic with all I/O (DB, Stripe, network) mocked or entirely absent. Fast
(<5s for the whole unit suite), run on every build.

**Primary targets**:
- `ProrationCalculator` — the single most heavily unit-tested class in the project (see §11 for the
  full case matrix). No Spring context, no mocks needed — pure function in, value out.
- `SubscriptionStateMachine` — every valid transition asserted to succeed; every invalid transition
  (e.g., `CANCELED -> ACTIVE` directly) asserted to throw `InvalidTransitionException`.
- `DunningPolicy` — given a failure timestamp and policy config, produces the correct list of
  scheduled attempt timestamps.
- `IdempotencyService` — request hashing is deterministic and canonicalized (same JSON body with
  different key ordering hashes identically); replay detection logic against mocked repository
  responses (row missing / in-progress / completed-same-hash / completed-different-hash).
- `AccessRestrictionService` — grace period boundary math (just before/at/just after expiry).
- DTO validation annotations (`@Valid` constraint tests) for request objects.
- `Money`/currency utility functions (formatting, cents↔display conversion).

**Tooling**: JUnit 5, AssertJ (fluent assertions, especially useful for asserting on
`ProrationResult` value objects and line-item lists), Mockito for any collaborator mocking
(e.g., mocking `PlanRepository` when unit-testing a service method that only needs plan lookup, not
a real DB).

**Convention**: unit tests for a service class mock **all** repositories and the Stripe SDK wrapper;
if a test needs a real database to make sense, it belongs in the integration suite, not here.

## 3. Integration Testing

**Scope**: Real Spring application context + real PostgreSQL via **Testcontainers** (not H2 — H2's
SQL dialect and constraint behavior diverge from Postgres in ways that specifically matter for this
project: `ON CONFLICT`, `jsonb`, `TIMESTAMPTZ`, unique-constraint race behavior). Stripe calls are
mocked at the SDK boundary (a `StripeClient` wrapper interface with a test double) or intercepted via
WireMock stubbing Stripe's HTTP API — **recommendation: wrap the Stripe SDK behind an internal
`StripePaymentGateway` interface** so integration tests can inject a fake implementation without
needing WireMock for most cases, reserving WireMock/Stripe test-mode for the smaller set of tests
that specifically validate webhook signature verification and request/response shape.

**Primary targets**:
- Full `POST /subscriptions` flow against a real (Testcontainers) Postgres: row inserted, idempotency
  key persisted, correct response shape — Stripe calls mocked.
- Full webhook receipt → dedup → dispatch → handler → DB state flow, using **real serialized Stripe
  event JSON fixtures** (captured from Stripe's test-mode event log or Stripe's official fixture
  examples) so the deserialization/mapping logic is genuinely exercised, not just a hand-rolled mock
  object that happens to match what the code expects.
- Flyway migrations apply cleanly from V1 on a fresh Testcontainers Postgres instance on every test
  run (catches migration drift immediately).
- Repository query correctness (e.g., `findByStatusAndScheduledAtBefore` for the dunning poller,
  partial-unique-index behavior for "at most one non-terminal subscription per customer").

**Tooling**: `@SpringBootTest` + Testcontainers PostgreSQL module, `spring-boot-testcontainers`
auto-configuration (`@ServiceConnection`), `MockMvc` or `WebTestClient` for HTTP-level assertions.

## 4. API Testing

**Scope**: Black-box HTTP contract testing — request in, response out, status codes, error envelope
shape, auth enforcement — via `MockMvc`/`WebTestClient` (or Postman/Newman collections as a
supplementary artifact for manual/CI smoke testing against a fully running Docker Compose stack).

**Coverage**:
- Every endpoint in ARCHITECTURE.md §9 has at least: one happy-path test, one auth-failure test
  (missing/expired JWT → 401), one authz-failure test where applicable (wrong role → 403), one
  validation-failure test (malformed body → 400).
- Idempotency-required endpoints have an explicit test for: missing `Idempotency-Key` header → 400.
- Pagination parameters on list endpoints (admin subscriptions/customers/audit logs) tested for
  boundary values (page 0, page beyond last, page size limits).
- OpenAPI/Swagger spec (generated via springdoc-openapi) is validated to be generatable without
  errors as part of CI — a cheap check that catches broken controller annotations early.

## 5. Stripe Webhook Testing

**Layered approach**:
1. **Unit-level handler tests**: each `WebhookEventHandler` implementation tested in isolation with
   a deserialized `Event` object built from a captured JSON fixture, repositories/services mocked —
   asserts the handler calls the right service methods with the right arguments.
2. **Integration-level dispatch tests**: `POST /webhooks/stripe` with a real signed payload (signed
   using the Stripe SDK's own `Webhook.constructEvent`/test signature helper against a known test
   webhook secret) against the real dispatcher + real DB (Testcontainers) + mocked downstream Stripe
   calls, asserting end-to-end state changes (e.g., posting a `checkout.session.completed` fixture
   results in the subscription row transitioning to `ACTIVE`).
3. **Manual/local verification**: using the **Stripe CLI** (`stripe listen --forward-to
   localhost:8080/api/v1/webhooks/stripe` and `stripe trigger <event-type>`) against a locally
   running Docker Compose stack — documented as a required manual verification step in the README
   before considering Phase 7 (Webhook Engine) complete, since it's the only way to be fully
   confident the signature verification works against Stripe's *real* signing behavior rather than a
   test helper's approximation of it.
4. **Signature verification negative tests**: tampered payload / wrong secret / missing header → 400,
   and — critically — assert the tampered request **never reaches** the dispatcher/handler (verified
   via a mock/spy asserting zero interactions), not merely that the HTTP response code is correct.

**Fixture management**: store representative Stripe event JSON payloads (with fake/sanitized IDs) in
`src/test/resources/stripe-fixtures/` — one file per event type used by the system
(`checkout.session.completed.json`, `invoice.payment_succeeded.json`, `invoice.payment_failed.json`,
`customer.subscription.updated.json`, `customer.subscription.deleted.json`) plus at least one
**malformed/unexpected-shape** fixture per type to test defensive parsing.

## 6. Idempotency Tests

- **Same key, same body, sequential**: second call returns the exact stored response (status +
  body), and — critically — asserted via a spy/mock or a DB-row-count check that the underlying
  mutation (e.g., subscription creation, Stripe call) executed **exactly once**.
- **Same key, different body**: second call returns 409, no mutation occurs.
- **Different key, same body**: both calls execute independently (two subscriptions created, or a
  409 from the business-level "already has an active subscription" rule — a different guard than
  idempotency, and the test should assert *which* guard fired).
- **Expired key**: a key past `expires_at` (simulated by manipulating the row directly in a test, or
  a test-only clock abstraction) is treated as if it doesn't exist — a new request with that key
  executes fresh rather than replaying.
- **Cleanup job**: `IdempotencyKeyCleanupJob` deletes only rows past `expires_at`, leaves others
  untouched — asserted with a mixed fixture of expired/non-expired rows.

## 7. Concurrency Tests

These are the tests that most directly validate the resume claims and deserve deliberate engineering
effort, not an afterthought:

- **Concurrent duplicate subscription creation**: fire N (e.g., 10) simultaneous
  `POST /subscriptions` requests with the **same** idempotency key and body from a test using a
  fixed-size thread pool / `CompletableFuture.allOf` (or an equivalent concurrency-test utility) —
  assert exactly one request performed the actual creation logic (one row in `subscriptions`, one
  Stripe Checkout Session call recorded against the mock gateway) and all N responses are either the
  identical success payload or a well-defined 409 for the ones that hit "in progress."
- **Concurrent webhook redelivery**: fire N simultaneous `POST /webhooks/stripe` with the **same**
  event ID/payload — assert the handler's business logic executed exactly once (e.g., a counter
  spy inside a test-double `NotificationSender`, or asserting the subscription's `updated_at`
  changed exactly once via version-column inspection).
- **Race between API-driven and webhook-driven subscription update**: a test that concurrently (a)
  calls `change-plan` and (b) delivers a `customer.subscription.updated` webhook for the same
  subscription, asserting the optimistic-locking retry logic (ARCHITECTURE.md/IMPLEMENTATION.md §15)
  results in both updates being applied without a lost update (final state reflects both changes, or
  a deterministic, explainable "last writer by event timestamp" outcome — not silent data loss).
- **Dunning scheduler double-execution guard**: simulate two scheduler invocations overlapping (e.g.,
  a slow first run still holding a row when a second poll fires) and assert the second run does not
  also execute the same `dunning_attempt` (via the `SELECT ... FOR UPDATE SKIP LOCKED`/atomic-update
  claim mechanism from IMPLEMENTATION.md §15).

**Tooling note**: implement these using JUnit 5 with `ExecutorService`-based fan-out and
`CountDownLatch` to maximize actual overlap (submitting all requests "at once" rather than
sequentially) — a test that just calls the endpoint 10 times in a `for` loop does **not** test
concurrency, it tests repetition, and this distinction should be respected in the actual test code.

## 8. Retry Testing (Dunning)

- Given a `PAST_DUE` subscription with a failed invoice and a configured policy `[1d, 3d, 5d, 7d]`,
  assert exactly 4 `dunning_attempts` rows are scheduled at the correct offsets from failure time.
- Simulate the scheduler firing when an attempt is due (manipulate `scheduled_at` into the past in
  the test) and the mock payment gateway returns failure → assert attempt marked `FAILED`, next
  attempt remains `PENDING`, subscription remains `PAST_DUE`, notification sent with correct
  "attempts remaining" count.
- Simulate the **final** attempt failing → assert subscription transitions to `UNPAID`, access
  restriction engaged, final-notice notification sent, no further `dunning_attempts` scheduled.
- Simulate a middle attempt **succeeding** → assert subscription transitions back to `ACTIVE`,
  remaining `PENDING` attempts are canceled (not left dangling), recovery notification sent.
- Simulate recovery via the **independent webhook path** (not the scheduler) arriving between two
  scheduled attempts → assert the same end state as scheduler-driven recovery, and assert the
  scheduler, when it later polls, correctly skips the now-`PAID` invoice's remaining attempts
  (`SKIPPED` status, not `FAILED`).

## 9. Failure Recovery / Resilience Testing

- **Stripe API transient failure during subscription creation**: mock gateway throws
  `APIConnectionException` on first call → assert the local `INCOMPLETE` row is not left in a
  permanently ambiguous state (either rolled back cleanly so the same idempotency key can retry, or
  marked in a way that a retry with the same key correctly resumes — whichever strategy is chosen
  per IMPLEMENTATION.md §11's note on failure handling, the test asserts that strategy is actually
  implemented).
- **Webhook handler throws mid-processing**: assert the `webhook_events` row is left `FAILED` (not
  silently lost), a subsequent redelivery of the same event ID is picked up and reprocessed (not
  treated as an already-`PROCESSED` no-op), and once it succeeds the row transitions to `PROCESSED`.
- **Reconciliation job self-healing**: seed a subscription whose `current_period_end` has passed with
  no recent webhook sync, run `SubscriptionRenewalReconciliationJob` against a mocked Stripe response
  reflecting the true current state, assert local state is corrected and a `WARN`-level audit/log
  entry is produced.

## 10. Database Testing

- Flyway migration test: fresh Testcontainers Postgres, run all migrations, assert no errors and
  assert key constraints exist (unique indexes, foreign keys) via a schema-introspection query — this
  catches "I meant to add a unique constraint but forgot" before it becomes a production bug.
- Constraint tests: attempt to insert two `idempotency_keys` rows with the same `idem_key` directly
  at the repository level (bypassing application logic) and assert a `DataIntegrityViolationException`
  — proves the safety net is a real DB constraint, not just application discipline.
- Same for `webhook_events.stripe_event_id`, `subscriptions.stripe_subscription_id`,
  `customers.stripe_customer_id`.
- Cascade/orphan behavior: deleting (or in practice, archiving) a `Plan` referenced by historical
  `Subscription` rows must not cascade-delete subscription history — assert `ON DELETE RESTRICT` (or
  that the application never hard-deletes plans, only archives via `is_active=false`) is enforced.

## 11. Proration Test Dataset (Edge Case Matrix)

A dedicated `ProrationCalculatorTest` parameterized test suite covering, at minimum:

| Case | Old Plan | New Plan | Period | Change Day | Expected Behavior |
|---|---|---|---|---|---|
| Mid-cycle upgrade | $10/mo | $30/mo | 30-day Jan period | Day 15 | Credit ~$5.00, debit ~$15.00, net +$10.00 |
| Mid-cycle downgrade | $30/mo | $10/mo | 30-day period | Day 15 | Credit ~$15.00, debit ~$5.00, net -$10.00 |
| Change on day 1 | any | any | any | Day 1 (full period remaining) | Credit = full old price, debit = full new price |
| Change on last day | any | any | any | Last day | Credit ≈ one day's old rate, debit ≈ one day's new rate (verify chosen inclusive/exclusive convention) |
| Leap-year February | $28/mo | $28/mo | Feb 1–29 (29-day period) | Day 14 | totalDays=29 used correctly, not hardcoded 30/31 |
| Annual period | $100/yr | $200/yr | 365-day period | Day 180 | Correct day-count over a long period, no overflow/precision loss |
| Lateral same-price change | $20/mo | $20/mo | any | any day | net = exactly $0.00, not ±$0.01 from rounding |
| Double change same period | $10→$20→$10 | — | 30-day period | Day 10, then Day 20 | Second proration computed against the *first new* plan as the "old" plan, correct compounding |
| Rounding stress case | $9.99/mo | $19.99/mo | 31-day period | Day 7 | Assert HALF_EVEN single-division rounding, not compounded per-day rounding drift |
| Change exactly at period boundary (changeAt == periodEnd) | any | any | any | == periodEnd | remainingDays clamped to 0, no negative/undefined behavior |

Each case asserts not just `netAmountCents` but the individual `creditCents`/`debitCents` and the
generated `lineItems` content, since a bug that produces the right net total via two wrong
intermediate numbers is exactly the kind of bug this suite exists to catch.

## 12. Additional Cross-Cutting Edge Cases

- JWT expiry mid-request-sequence (access token expires between two frontend calls) → 401 → frontend
  refresh flow → retried original request succeeds (tested at the frontend integration level, e.g.
  with MSW-mocked API in a React Testing Library test, or documented as a manual QA step if frontend
  test tooling is descoped).
- Admin force-cancel on a subscription that a webhook is *simultaneously* trying to mark `ACTIVE`
  (e.g., a late `invoice.payment_succeeded` arrives after admin cancellation) — asserted per the
  "don't resurrect a CANCELED subscription" business rule in IMPLEMENTATION.md §16.
- Plan archived (`is_active=false`) while a customer has it open in their upgrade-selection UI and
  submits a change to it → backend rejects with a clear 409/400, not a silent success referencing a
  dead plan.

## 13. Expected Coverage

- **Overall backend line coverage target: ≥ 80%**, measured via JaCoCo in the Maven build, enforced
  as a CI check that fails the build below threshold (configurable, not a vanity metric — the
  threshold exists to prevent the highest-risk modules from silently losing coverage over time).
- **Targeted higher bar for critical modules** (checked via JaCoCo per-package rules, not just the
  global number): `proration/`, `idempotency/`, `webhook/`, `dunning/` packages target **≥ 90% line
  coverage and, more importantly, explicit coverage of every branch identified in the edge-case
  tables above** — coverage percentage alone is an inadequate proxy for these modules, so the CI gate
  is supplemented by a documented checklist (this file) that a reviewer (or the implementer
  self-reviewing) checks off manually.
- Frontend: component/hook unit tests via Vitest + React Testing Library for the billing-critical
  components (`ProrationPreview`, `CheckoutForm`, subscription status banner logic) — target
  meaningful coverage of conditional rendering logic (e.g., "what does the dashboard show for each
  of the 6 subscription statuses") over blanket percentage targets.
- CI pipeline fails the build on: any test failure, coverage threshold breach, or Flyway migration
  validation failure — all three are hard gates, not warnings.
