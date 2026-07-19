# TASKS.md — Ordered Implementation Task List

Conventions for every task below:
- **Goal**: what this task accomplishes and why.
- **Files**: files to create/modify (paths relative to repo root per PROJECT.md §8).
- **Depends on**: prior task IDs that must be complete first.
- **Acceptance criteria**: concrete, checkable conditions (tests passing, endpoint behavior, etc.).
- **Complexity**: S (small, <1hr), M (medium, 1–3hr), L (large, half day+).

Tasks are ordered for sequential execution. Do not skip ahead — later tasks assume earlier ones are
fully complete and merged, matching the phases in IMPLEMENTATION.md §1.

---

## Phase 0 — Repository & Infra Foundations

### T001 — Initialize repository structure
- **Goal**: Create the top-level folder skeleton so subsequent tasks have a home.
- **Files**: `backend/` (empty placeholder), `frontend/` (empty placeholder), `infra/`, `docs/`,
  `.github/workflows/`, `.gitignore`, `.env.example`, `README.md`
- **Depends on**: none
- **Acceptance criteria**: Directory structure matches PROJECT.md §8 top level; `.gitignore`
  excludes `target/`, `node_modules/`, `.env`, `dist/`; `README.md` has a project title and one-
  paragraph description.
- **Complexity**: S

### T002 — Scaffold Spring Boot backend project
- **Goal**: Bootable Spring Boot 3 / Java 21 Maven project with core dependencies.
- **Files**: `backend/pom.xml`, `backend/src/main/java/com/sbp/SbpApplication.java`,
  `backend/src/main/resources/application.yml`
- **Depends on**: T001
- **Acceptance criteria**: `mvn spring-boot:run` starts without errors on a default port; `pom.xml`
  includes `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`,
  `spring-boot-starter-validation`, `flyway-core`, `postgresql` driver, Stripe Java SDK, `jjwt` (or
  chosen JWT lib), Lombok (optional), test dependencies (JUnit 5, Mockito, AssertJ, Testcontainers).
- **Complexity**: S

### T003 — Add Postgres + Docker Compose for local dev
- **Goal**: One-command local environment (`docker compose up`) with Postgres available to the
  backend.
- **Files**: `infra/docker-compose.yml`, `backend/Dockerfile`, `.env.example`
- **Depends on**: T002
- **Acceptance criteria**: `docker compose up` starts a Postgres container reachable on the
  configured port; backend `application-dev.yml` points at it via env vars; healthcheck defined for
  Postgres service.
- **Complexity**: S

### T004 — Add Flyway baseline migration + core lookup tables
- **Goal**: Establish migration-driven schema management from day one (never `ddl-auto`).
- **Files**: `backend/src/main/resources/db/migration/V1__init_schema.sql`,
  `backend/src/main/resources/application.yml` (Flyway config)
- **Depends on**: T003
- **Acceptance criteria**: `V1__init_schema.sql` creates `users` and `plans` tables (see
  ARCHITECTURE.md §8) with correct types/constraints; backend startup runs the migration
  successfully against the Docker Compose Postgres; `spring.jpa.hibernate.ddl-auto=validate` is set
  (never `update`/`create`).
- **Complexity**: M

### T005 — Set up GitHub Actions CI skeleton
- **Goal**: CI runs backend build/tests on every push/PR from the start.
- **Files**: `.github/workflows/backend-ci.yml`
- **Depends on**: T004
- **Acceptance criteria**: Workflow spins up a Postgres service container, runs `mvn -B verify`,
  fails the build on test failure; badge added to `README.md`.
- **Complexity**: S

---

## Phase 1 — Identity & Auth

### T006 — User entity, repository, and remaining auth-related migration
- **Goal**: Persist users with hashed passwords and roles.
- **Files**: `backend/src/main/java/com/sbp/user/User.java`, `Role.java`,
  `UserRepository.java`, `db/migration/V2__auth_tables.sql` (refresh_tokens table)
- **Depends on**: T004
- **Acceptance criteria**: `User` entity maps to `users` table; unique constraint on `email` enforced
  at DB level; repository integration test (Testcontainers) confirms duplicate email insert throws.
- **Complexity**: S

### T007 — Password hashing + registration endpoint
- **Goal**: `POST /api/v1/auth/register` creates a user with a BCrypt-hashed password.
- **Files**: `backend/src/main/java/com/sbp/auth/AuthController.java`, `AuthService.java`,
  `dto/RegisterRequest.java`, `dto/RegisterResponse.java`, `config/SecurityConfig.java` (permit this
  route)
- **Depends on**: T006
- **Acceptance criteria**: Valid registration returns 201 with no password in the response body;
  duplicate email returns 409; invalid email/short password returns 400; unit test confirms stored
  password is not plaintext.
- **Complexity**: M

### T008 — JWT provider + login endpoint
- **Goal**: `POST /api/v1/auth/login` authenticates and returns an access + refresh token pair.
- **Files**: `security/JwtTokenProvider.java`, `auth/dto/LoginRequest.java`, `TokenResponse.java`,
  `AuthService.java` (extend), `AuthController.java` (extend)
- **Depends on**: T007
- **Acceptance criteria**: Correct credentials return a valid JWT (decodable, correct `sub`/`role`
  claims, correct expiry per ARCHITECTURE.md §3); wrong password returns 401 without revealing
  whether the email exists; refresh token stored hashed in `refresh_tokens`.
- **Complexity**: M

### T009 — JWT authentication filter + security config wiring
- **Goal**: Protect endpoints, populate `SecurityContext` from a valid bearer token.
- **Files**: `security/JwtAuthenticationFilter.java`, `security/CustomUserDetailsService.java`,
  `security/SecurityUser.java`, `config/SecurityConfig.java` (finalize filter chain, role rules)
- **Depends on**: T008
- **Acceptance criteria**: A protected test endpoint returns 401 with no/invalid/expired token and
  200 with a valid token; role-restricted test endpoint returns 403 for wrong role; integration test
  covers all three cases.
- **Complexity**: M

### T010 — Refresh token endpoint + logout (revocation)
- **Goal**: `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`.
- **Files**: `AuthController.java`, `AuthService.java`, `RefreshTokenRepository.java`,
  `dto/RefreshRequest.java`
- **Depends on**: T009
- **Acceptance criteria**: Valid refresh token issues a new access token; revoked/expired/unknown
  refresh token returns 401; logout marks the refresh token `revoked=true`, and a subsequent refresh
  attempt with it fails.
- **Complexity**: M

### T011 — Global exception handler + standard error envelope
- **Goal**: Consistent error JSON shape across the API from the start (used by every later endpoint).
- **Files**: `common/exception/GlobalExceptionHandler.java`, `common/exception/SbpException.java`
  hierarchy (`NotFoundException`, `ConflictException`, `ForbiddenException`, `BadRequestException`)
- **Depends on**: T009
- **Acceptance criteria**: Each exception type maps to the documented HTTP status
  (ARCHITECTURE.md §9 / IMPLEMENTATION.md §14); unhandled exceptions return 500 with no stack trace
  in the response body; unit tests cover each mapping.
- **Complexity**: S

---

## Phase 2 — Plan Catalog

### T012 — Plan entity + migration + admin CRUD
- **Goal**: Admins can manage the plan catalog.
- **Files**: `plan/Plan.java`, `PlanRepository.java`, `PlanService.java`, `PlanController.java`
  (admin routes), `dto/PlanRequest.java`, `dto/PlanResponse.java`,
  `db/migration/V3__plans_table.sql` (if not already covered in V1)
- **Depends on**: T011
- **Acceptance criteria**: Admin can create a plan (`stripe_price_id`, `price_cents`, `currency`,
  `billing_interval`, `name`); non-admin gets 403; `PATCH` can archive (`is_active=false`) a plan;
  archived plans excluded from the public list endpoint.
- **Complexity**: M

### T013 — Public plan listing endpoint
- **Goal**: `GET /api/v1/plans` for the checkout/pricing page.
- **Files**: `PlanController.java` (extend)
- **Depends on**: T012
- **Acceptance criteria**: Returns only `is_active=true` plans, no auth required, correct DTO shape
  (no internal IDs beyond what the frontend needs).
- **Complexity**: S

### T014 — Seed script / dev fixtures for plans matching Stripe test-mode Prices
- **Goal**: Reproducible local dev data aligned with real Stripe test-mode objects.
- **Files**: `infra/postgres/init/seed_plans.sql` or a Spring `CommandLineRunner` gated to the `dev`
  profile
- **Depends on**: T013
- **Acceptance criteria**: Fresh `docker compose up` environment has 3–4 usable plans whose
  `stripe_price_id` values correspond to Prices the implementer has created in their own Stripe test
  account (documented placeholder IDs with a README note on how to replace them).
- **Complexity**: S

---

## Phase 3 — Stripe Customer Bootstrap

### T015 — StripePaymentGateway abstraction + config
- **Goal**: Wrap the Stripe SDK behind an internal interface for testability (per TESTING.md §3).
- **Files**: `config/StripeConfig.java`, `payment/StripePaymentGateway.java` (interface),
  `payment/StripePaymentGatewayImpl.java`
- **Depends on**: T002
- **Acceptance criteria**: `StripeConfig` reads the secret key from environment/`.env`; interface
  exposes the minimal method set needed by later tasks (create customer, create checkout session,
  update subscription, pay invoice); a no-op/fake implementation exists for tests.
- **Complexity**: M

### T016 — Customer entity + Stripe customer creation
- **Goal**: Link an internal user to a Stripe Customer.
- **Files**: `customer/Customer.java`, `CustomerRepository.java`, `CustomerService.java`,
  `db/migration/V4__customers_table.sql`
- **Depends on**: T015
- **Acceptance criteria**: `CustomerService.getOrCreateForUser(userId)` returns an existing row if
  present, otherwise calls the gateway to create a Stripe Customer and persists it; unit test with a
  fake gateway confirms idempotent "get or create" behavior (second call doesn't create a duplicate
  Stripe customer).
- **Complexity**: M

---

## Phase 4 — Idempotency Infrastructure

### T017 — Idempotency key table + repository
- **Goal**: Persistence layer for idempotency keys.
- **Files**: `idempotency/IdempotencyKey.java`, `IdempotencyKeyRepository.java`,
  `db/migration/V5__idempotency_keys.sql`
- **Depends on**: T011
- **Acceptance criteria**: Unique constraint on `idem_key`; repository test confirms
  `INSERT ... ON CONFLICT DO NOTHING`-style atomic claim method behaves correctly under a direct
  duplicate insert attempt.
- **Complexity**: S

### T018 — IdempotencyService (claim / replay / conflict logic)
- **Goal**: Implement the core logic from IMPLEMENTATION.md §11, independent of any specific
  endpoint.
- **Files**: `idempotency/IdempotencyService.java`
- **Depends on**: T017
- **Acceptance criteria**: Unit tests (mocked repository) cover: no existing key → claims and
  proceeds; existing key same hash completed → returns stored response; existing key different hash
  → throws `ConflictException`; existing key in-progress → throws `ConflictException`.
- **Complexity**: M

### T019 — @IdempotentEndpoint annotation + AOP aspect
- **Goal**: Cross-cutting enforcement mechanism wiring `IdempotencyService` into any annotated
  controller method.
- **Files**: `idempotency/IdempotentEndpoint.java` (annotation), `IdempotentRequestInterceptor.java`
  (aspect)
- **Depends on**: T018
- **Acceptance criteria**: A throwaway test controller method annotated `@IdempotentEndpoint`
  demonstrates: missing header → 400; duplicate same-body request → second call doesn't re-execute
  the method body (verified via a call counter); full concurrency test (per TESTING.md §7) passes
  for this test endpoint before moving on to real endpoints.
- **Complexity**: L

### T020 — Idempotency key cleanup job
- **Goal**: Background purge of expired keys.
- **Files**: `scheduling/IdempotencyKeyCleanupJob.java`, `IdempotencyService.java` (add
  `deleteExpired` method)
- **Depends on**: T019
- **Acceptance criteria**: Unit test with mixed expired/non-expired fixture rows confirms only
  expired rows deleted; job registered with `@Scheduled` and a configurable cron in
  `application.yml`.
- **Complexity**: S

---

## Phase 5 — Subscription Creation & Checkout

### T021 — Subscription entity, status enum, migration
- **Goal**: Core aggregate for the rest of the project.
- **Files**: `subscription/Subscription.java`, `SubscriptionStatus.java`,
  `db/migration/V6__subscriptions_table.sql` (include `version` column for optimistic locking, and a
  partial unique index enforcing "at most one non-terminal subscription per customer" per
  IMPLEMENTATION.md §16)
- **Depends on**: T016
- **Acceptance criteria**: `@Version` field present and confirmed to increment on update in a
  repository test; partial unique index confirmed via a test that attempts to insert two
  non-terminal subscriptions for the same customer and expects a constraint violation.
- **Complexity**: M

### T022 — SubscriptionStateMachine
- **Goal**: Centralize valid state transitions per ARCHITECTURE.md §5.
- **Files**: `subscription/SubscriptionStateMachine.java`
- **Depends on**: T021
- **Acceptance criteria**: Unit test table covering every edge in the state diagram (valid) and a
  representative set of invalid transitions (e.g., `CANCELED -> ACTIVE`), each asserted to throw
  `InvalidTransitionException`.
- **Complexity**: M

### T023 — POST /subscriptions (creation + Checkout Session)
- **Goal**: Implement IMPLEMENTATION.md §2 end-to-end, wired through idempotency (T019).
- **Files**: `subscription/SubscriptionController.java`, `SubscriptionService.java`,
  `SubscriptionRepository.java`, `dto/CreateSubscriptionRequest.java`,
  `dto/CreateSubscriptionResponse.java`
- **Depends on**: T022, T019
- **Acceptance criteria**: Happy path returns 201 with `checkoutUrl`; local row created as
  `INCOMPLETE` with `client_reference_id`/metadata set to the local subscription ID; attempting to
  subscribe again while a non-terminal subscription exists returns 409; idempotent replay confirmed
  via integration test (Testcontainers + fake gateway).
- **Complexity**: L

### T024 — GET /subscriptions/me
- **Goal**: Customer can view their current subscription state.
- **Files**: `SubscriptionController.java` (extend), `dto/SubscriptionResponse.java`
- **Depends on**: T023
- **Acceptance criteria**: Returns the authenticated user's current subscription (or 404/empty state
  if none); another user's subscription is never returned (ownership check tested explicitly).
- **Complexity**: S

---

## Phase 6 — Webhook Engine

### T025 — WebhookEvent entity + repository + migration
- **Goal**: Durable, deduplicated event log (ARCHITECTURE.md §6).
- **Files**: `webhook/WebhookEvent.java`, `WebhookEventStatus.java`, `WebhookEventRepository.java`,
  `db/migration/V7__webhook_events.sql`
- **Depends on**: T011
- **Acceptance criteria**: Unique constraint on `stripe_event_id`; repository test confirms atomic
  claim-insert behavior under a simulated duplicate.
- **Complexity**: S

### T026 — Signature verification + webhook controller skeleton
- **Goal**: `POST /api/v1/webhooks/stripe` verifies signatures and persists events before any
  processing.
- **Files**: `webhook/StripeWebhookController.java`, `config/StripeConfig.java` (extend with signing
  secret), `db/migration` (none needed, reuse T025)
- **Depends on**: T025
- **Acceptance criteria**: Valid signed test fixture → 200, row persisted `RECEIVED`; tampered
  payload/wrong secret → 400, no row persisted, and a mock/spy confirms zero downstream handler
  interaction (per TESTING.md §5 point 4).
- **Complexity**: M

### T027 — WebhookEventHandler interface + dispatcher (strategy pattern)
- **Goal**: Route events to per-type handlers per ARCHITECTURE.md §10.
- **Files**: `webhook/handlers/WebhookEventHandler.java`, `webhook/WebhookDispatcher.java`
- **Depends on**: T026
- **Acceptance criteria**: Dispatcher resolves handler by event type from a registered map; unknown
  event type marks the event `PROCESSED` (no-op) rather than failing; unit test confirms both paths.
- **Complexity**: M

### T028 — CheckoutSessionCompletedHandler
- **Goal**: Activate a subscription on successful checkout.
- **Files**: `webhook/handlers/CheckoutSessionCompletedHandler.java`, fixture
  `stripe-fixtures/checkout.session.completed.json`
- **Depends on**: T027, T024
- **Acceptance criteria**: Given the fixture with `client_reference_id` matching a local
  `INCOMPLETE` subscription, handler transitions it to `ACTIVE` and populates
  `stripe_subscription_id`, `current_period_start/end`; integration test confirms via
  `POST /webhooks/stripe` end-to-end, and confirms redelivering the same event is a no-op (second
  POST doesn't re-run handler logic — assert via spy).
- **Complexity**: M

### T029 — CustomerSubscriptionUpdatedHandler
- **Goal**: Keep local subscription in sync with Stripe-side updates.
- **Files**: `webhook/handlers/CustomerSubscriptionUpdatedHandler.java`, fixture
  `customer.subscription.updated.json`, `Subscription.java` (add `lastSyncedFromStripeAt` field +
  migration if not already present)
- **Depends on**: T028
- **Acceptance criteria**: Implements the stale-event guard from IMPLEMENTATION.md §9; unit test
  confirms an older event (by event timestamp) is ignored when a newer sync has already been applied;
  unrecognized `stripe_subscription_id` logs and no-ops without throwing.
- **Complexity**: M

### T030 — CustomerSubscriptionDeletedHandler
- **Goal**: Handle Stripe-side cancellation.
- **Files**: `webhook/handlers/CustomerSubscriptionDeletedHandler.java`, fixture
  `customer.subscription.deleted.json`
- **Depends on**: T029
- **Acceptance criteria**: Transitions the local subscription to `CANCELED` via the state machine;
  idempotent on redelivery (already-`CANCELED` stays `CANCELED`, no error).
- **Complexity**: S

---

## Phase 7 — Invoicing

### T031 — Invoice + InvoiceLineItem entities + migration
- **Goal**: Persistence for IMPLEMENTATION.md §6.
- **Files**: `billing/invoice/Invoice.java`, `InvoiceLineItem.java`, `InvoiceStatus.java`,
  `InvoiceRepository.java`, `db/migration/V8__invoices.sql`
- **Depends on**: T029
- **Acceptance criteria**: Unique constraint on `stripe_invoice_id`; foreign key to `subscriptions`;
  repository test for upsert-by-unique-key behavior.
- **Complexity**: S

### T032 — InvoiceService.syncInvoiceFromStripe + payment-status webhook handlers
- **Goal**: Implement `InvoicePaymentSucceededHandler` and `InvoicePaymentFailedHandler`.
- **Files**: `billing/invoice/InvoiceService.java`,
  `webhook/handlers/InvoicePaymentSucceededHandler.java`,
  `webhook/handlers/InvoicePaymentFailedHandler.java`, fixtures
  `invoice.payment_succeeded.json`, `invoice.payment_failed.json`
- **Depends on**: T031
- **Acceptance criteria**: Both handlers correctly upsert an `Invoice` row; unit test confirms
  calling `syncInvoiceFromStripe` twice with the same payload is idempotent (same end state, no
  duplicate row); `invoice.payment_failed` handler transitions the related subscription to
  `PAST_DUE` via the state machine.
- **Complexity**: M

### T033 — GET /invoices/me and GET /invoices/{id}
- **Goal**: Customer-facing invoice history.
- **Files**: `billing/invoice/InvoiceController.java`, `dto/InvoiceResponse.java`
- **Depends on**: T032
- **Acceptance criteria**: Paginated list of the authenticated customer's invoices; detail endpoint
  includes line items; ownership check prevents fetching another customer's invoice (403/404).
- **Complexity**: M

---

## Phase 8 — Proration Engine

### T034 — ProrationCalculator (pure logic)
- **Goal**: Implement the algorithm from IMPLEMENTATION.md §5 with full unit test matrix.
- **Files**: `proration/ProrationCalculator.java`, `ProrationResult.java`, `ProrationLineItem.java`
- **Depends on**: T021
- **Acceptance criteria**: Every row in TESTING.md §11's edge-case matrix has a passing parameterized
  test; no Spring context required to run this test class; HALF_EVEN single-division rounding
  verified explicitly in a dedicated test.
- **Complexity**: L

### T035 — LedgerEntry entity + migration
- **Goal**: Append-only financial ledger backing proration auditability.
- **Files**: `billing/ledger/LedgerEntry.java`, `LedgerEntryType.java`, `LedgerRepository.java`,
  `db/migration/V9__ledger_entries.sql`
- **Depends on**: T034
- **Acceptance criteria**: Table created with FK to `subscriptions` and nullable FK to `invoices`;
  no update/delete operations exposed on the repository interface beyond insert/read (enforces
  append-only at the code level).
- **Complexity**: S

### T036 — POST /subscriptions/me/preview-change
- **Goal**: Dry-run proration preview endpoint, no side effects.
- **Files**: `SubscriptionController.java` (extend), `SubscriptionService.java` (extend),
  `dto/ProrationPreviewResponse.java`
- **Depends on**: T035
- **Acceptance criteria**: Returns a `ProrationResult`-shaped response without writing to
  `ledger_entries`/`invoices`/mutating the subscription; integration test confirms zero DB writes
  occur to those tables when this endpoint is called.
- **Complexity**: M

### T037 — POST /subscriptions/me/change-plan (apply upgrade/downgrade)
- **Goal**: Implement IMPLEMENTATION.md §4 end-to-end, wired through idempotency.
- **Files**: `SubscriptionController.java` (extend), `SubscriptionService.java` (extend),
  `dto/ChangePlanRequest.java`
- **Depends on**: T036, T019
- **Acceptance criteria**: On success: subscription's `planId` updated, one adjustment `Invoice` +
  `InvoiceLineItem` rows + paired `LedgerEntry` rows created, all in one transaction (verified via a
  test that forces a failure after the Stripe call succeeds but before commit, asserting rollback of
  ALL rows, not partial); rejects the call when current subscription status is not `ACTIVE`; rejects
  same-plan "change"; the "double change in one period" and "downgrade producing net credit" edge
  cases from IMPLEMENTATION.md §5/§16 each have a dedicated integration test.
- **Complexity**: L

---

## Phase 9 — Dunning Workflow

### T038 — DunningAttempt entity + DunningPolicy config + migration
- **Goal**: Data model and configurable policy for IMPLEMENTATION.md §8.
- **Files**: `dunning/DunningAttempt.java`, `DunningPolicy.java` (config-bound, e.g.
  `@ConfigurationProperties`), `DunningAttemptRepository.java`,
  `db/migration/V10__dunning_attempts.sql`, `application.yml` (dunning offsets config)
- **Depends on**: T032
- **Acceptance criteria**: `DunningPolicy` correctly binds `dunning.retry-offsets` (list of durations)
  from YAML; unit test confirms default `[1d, 3d, 5d, 7d]` loads correctly and is overridable via
  test profile config.
- **Complexity**: S

### T039 — PaymentAttempt entity + migration
- **Goal**: Raw record of each Stripe charge attempt, distinct from `dunning_attempts`.
- **Files**: `payment/PaymentAttempt.java`, `PaymentAttemptRepository.java`,
  `db/migration/V11__payment_attempts.sql`
- **Depends on**: T038
- **Acceptance criteria**: FK to `invoices`; stores `status`, `failure_code`, `failure_message`.
- **Complexity**: S

### T040 — NotificationSender interface + LoggingNotificationSender
- **Goal**: Adapter-pattern notification abstraction per ARCHITECTURE.md §10.
- **Files**: `notification/NotificationSender.java`, `LoggingNotificationSender.java`,
  `NotificationTemplate.java` (enum: `PAYMENT_FAILED`, `PAYMENT_RECOVERED`, `RETRY_FAILED`,
  `FINAL_NOTICE`)
- **Depends on**: T002
- **Acceptance criteria**: `LoggingNotificationSender` logs a structured message per template with
  customer/subscription context; registered as the default Spring bean; unit test asserts correct
  log content per template type (using a log-capturing test appender).
- **Complexity**: S

### T041 — DunningService.scheduleRetries + wiring into InvoicePaymentFailedHandler
- **Goal**: On first failure, schedule the retry chain (IMPLEMENTATION.md §8 "On first failure").
- **Files**: `dunning/DunningService.java`, `webhook/handlers/InvoicePaymentFailedHandler.java`
  (extend from T032)
- **Depends on**: T039, T040
- **Acceptance criteria**: Given a failed-invoice webhook, exactly the configured number of
  `dunning_attempts` rows are created at the correct scheduled offsets (TESTING.md §8 first bullet);
  a `PAYMENT_FAILED` notification is sent; subscription transitions to `PAST_DUE`.
- **Complexity**: M

### T042 — AccessRestrictionService
- **Goal**: Implement grace-period access gating per IMPLEMENTATION.md §13.
- **Files**: `dunning/AccessRestrictionService.java`
- **Depends on**: T038
- **Acceptance criteria**: Unit tests cover boundary conditions (just before/at/after grace period
  expiry) for `PAST_DUE`; `ACTIVE` always allowed; all terminal-negative statuses always denied.
- **Complexity**: S

### T043 — DunningScheduler + DunningService.executeAttempt
- **Goal**: The polling job that actually executes due retries (IMPLEMENTATION.md §8 pseudocode).
- **Files**: `dunning/DunningScheduler.java`, `DunningService.java` (extend with `executeAttempt`)
- **Depends on**: T041, T042
- **Acceptance criteria**: All four TESTING.md §8 scenarios (failure → next pending, final failure →
  UNPAID + restriction, mid-chain success → ACTIVE + cancel remaining, already-paid → SKIPPED) pass
  as integration tests; scheduler registered with configurable `@Scheduled(fixedDelay=...)`.
- **Complexity**: L

### T044 — Recovery via InvoicePaymentSucceededHandler (independent webhook path)
- **Goal**: Implement the second recovery path from IMPLEMENTATION.md §8 and prove both paths
  converge safely.
- **Files**: `webhook/handlers/InvoicePaymentSucceededHandler.java` (extend from T032)
- **Depends on**: T043
- **Acceptance criteria**: Webhook-driven recovery test passes (TESTING.md §8 last bullet);
  concurrency test simulating both recovery paths racing (TESTING.md §7 dunning-adjacent case, or a
  dedicated test) confirms no double-notification and no inconsistent final state.
- **Complexity**: M

### T045 — Sample gated endpoint using AccessRestrictionService
- **Goal**: Concrete, demonstrable proof that dunning status actually blocks access
  (IMPLEMENTATION.md §13).
- **Files**: `demo/PremiumContentController.java` (or similarly named minimal sample resource)
- **Depends on**: T044
- **Acceptance criteria**: `ACTIVE` subscription → 200; `PAST_DUE` within grace period → 200;
  `PAST_DUE` past grace period / `UNPAID` → 403 with a clear error body explaining why.
- **Complexity**: S

---

## Phase 10 — Background Jobs (Remaining)

### T046 — SubscriptionRenewalReconciliationJob
- **Goal**: Self-healing safety net per IMPLEMENTATION.md §7.
- **Files**: `scheduling/SubscriptionRenewalReconciliationJob.java`, `SubscriptionService.java`
  (extend with a `reconcileWithStripe` method)
- **Depends on**: T029
- **Acceptance criteria**: Unit test with a fake gateway confirms a subscription whose period end has
  passed without a recent sync gets corrected and logged at `WARN`.
- **Complexity**: M

### T047 — CheckoutSessionExpirySweepJob
- **Goal**: Clean up abandoned `INCOMPLETE` subscriptions per IMPLEMENTATION.md §16.
- **Files**: `scheduling/CheckoutSessionExpirySweepJob.java`
- **Depends on**: T023
- **Acceptance criteria**: `INCOMPLETE` subscriptions older than the configured window transition to
  `EXPIRED`; confirmed via unit test with a manipulated `created_at`.
- **Complexity**: S

---

## Phase 11 — Cancellation & Admin Tooling

### T048 — POST /subscriptions/me/cancel
- **Goal**: Customer-initiated cancellation (immediate or at period end), idempotent.
- **Files**: `SubscriptionController.java` (extend), `SubscriptionService.java` (extend),
  `dto/CancelSubscriptionRequest.java`
- **Depends on**: T037, T019
- **Acceptance criteria**: Both immediate and at-period-end modes tested; wired through the
  idempotency aspect; Stripe gateway called to cancel the corresponding subscription.
- **Complexity**: M

### T049 — AuditLog entity + AuditLogService + retrofit into prior services
- **Goal**: Central audit trail per ARCHITECTURE.md §8.2, referenced throughout IMPLEMENTATION.md.
- **Files**: `audit/AuditLog.java`, `AuditLogRepository.java`, `AuditLogService.java`,
  `db/migration/V12__audit_logs.sql`; then small edits across `SubscriptionService`,
  `DunningService`, webhook handlers to call `auditLog.record(...)` at the points specified in
  IMPLEMENTATION.md (e.g., `SUBSCRIPTION_CREATE_INITIATED`, `DUNNING_STARTED`,
  `DUNNING_RECOVERED`, `DUNNING_EXHAUSTED`)
- **Depends on**: T048
- **Acceptance criteria**: Each named audit action point in IMPLEMENTATION.md has a corresponding
  `auditLog.record(...)` call verified by a unit/integration test asserting a row is created with the
  correct `action` code.
- **Complexity**: M

### T050 — Admin endpoints: list subscriptions/customers, force-cancel, manual retry
- **Goal**: Implement FR35/FR36.
- **Files**: `admin/AdminController.java`, `AdminService.java`, `dto/AdminSubscriptionResponse.java`,
  etc.
- **Depends on**: T049
- **Acceptance criteria**: All routes require `ADMIN` role (403 otherwise); list endpoints support
  pagination and at least one filter (e.g., by status); force-cancel goes through the same state
  machine as customer cancellation; manual retry triggers `DunningService.executeAttempt` (or a
  variant) outside the normal schedule and is auditable.
- **Complexity**: M

### T051 — GET /admin/audit-logs
- **Goal**: Expose the audit trail for admin inspection.
- **Files**: `AdminController.java` (extend)
- **Depends on**: T050
- **Acceptance criteria**: Paginated, filterable by `entity_type`/`entity_id`; admin-only.
- **Complexity**: S

---

## Phase 12 — Backend Hardening

### T052 — Concurrency test suite
- **Goal**: Implement all scenarios from TESTING.md §7 as dedicated integration tests, if not already
  covered incrementally in earlier tasks.
- **Files**: `backend/src/test/java/com/sbp/concurrency/*Test.java`
- **Depends on**: T050
- **Acceptance criteria**: All four concurrency scenarios pass reliably across multiple runs (no
  flakiness — run each test 5x locally as a sanity check before considering this task done).
- **Complexity**: L

### T053 — JaCoCo coverage gate + per-package thresholds
- **Goal**: Enforce TESTING.md §13 coverage targets in CI.
- **Files**: `backend/pom.xml` (JaCoCo plugin config), `.github/workflows/backend-ci.yml` (extend)
- **Depends on**: T052
- **Acceptance criteria**: Build fails if overall coverage < 80% or if `proration`/`idempotency`/
  `webhook`/`dunning` packages fall below 90%; current codebase passes the gate.
- **Complexity**: S

### T054 — OpenAPI/Swagger documentation
- **Goal**: Auto-generated, always-current API docs.
- **Files**: `config/OpenApiConfig.java`, `pom.xml` (springdoc-openapi dependency)
- **Depends on**: T051
- **Acceptance criteria**: `/v3/api-docs` and Swagger UI accessible in dev; CI step confirms spec
  generation doesn't error.
- **Complexity**: S

---

## Phase 13 — Frontend Foundations

### T055 — Scaffold React + TypeScript + Tailwind + Vite project
- **Goal**: Frontend project skeleton.
- **Files**: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/tailwind.config.ts`,
  `frontend/vite.config.ts`, `frontend/src/main.tsx`, `frontend/src/App.tsx`
- **Depends on**: T001
- **Acceptance criteria**: `npm run dev` serves a blank app; `npm run build` succeeds; Tailwind
  classes render correctly in a smoke-test component.
- **Complexity**: S

### T056 — Axios client + auth interceptors + React Query provider
- **Goal**: Central HTTP/data-fetching setup used by every subsequent page.
- **Files**: `frontend/src/api/axiosClient.ts`, `frontend/src/App.tsx` (wrap with
  `QueryClientProvider`), `frontend/src/context/AuthContext.tsx`
- **Depends on**: T055
- **Acceptance criteria**: Axios instance attaches `Authorization: Bearer <token>` from context;
  response interceptor handles 401 by attempting a refresh (T010 endpoint) once, then redirecting to
  login if that also fails.
- **Complexity**: M

### T057 — Auth pages (Login, Register) + ProtectedRoute
- **Goal**: Working end-to-end auth UX against the real backend.
- **Files**: `frontend/src/pages/LoginPage.tsx`, `RegisterPage.tsx`,
  `frontend/src/components/layout/ProtectedRoute.tsx`, `frontend/src/api/authApi.ts`
- **Depends on**: T056, T010 (backend)
- **Acceptance criteria**: Manual QA: register → login → land on a protected dashboard placeholder;
  logging out clears tokens and redirects to login; unauthenticated access to a protected route
  redirects to login.
- **Complexity**: M

### T058 — Docker Compose integration for frontend + full-stack `docker compose up`
- **Goal**: Entire stack runs with one command.
- **Files**: `frontend/Dockerfile`, `infra/docker-compose.yml` (extend with frontend service)
- **Depends on**: T057, T003
- **Acceptance criteria**: `docker compose up` from `infra/` brings up Postgres, backend, and
  frontend, reachable and functional together (manual QA: register/login through the composed
  stack).
- **Complexity**: M

### T059 — GitHub Actions frontend CI
- **Goal**: Lint/type-check/test/build the frontend on every push.
- **Files**: `.github/workflows/frontend-ci.yml`
- **Depends on**: T058
- **Acceptance criteria**: Workflow runs `npm ci`, `npm run lint`, `npm run typecheck` (or `tsc
  --noEmit`), `npm run build`; fails on any error.
- **Complexity**: S

---

## Phase 14 — Frontend Billing UX

### T060 — Plans page (pricing cards) + Stripe Checkout redirect
- **Goal**: Customer can browse plans and initiate a subscription.
- **Files**: `frontend/src/pages/PlansPage.tsx`, `frontend/src/api/planApi.ts`,
  `frontend/src/api/subscriptionApi.ts`, `frontend/src/components/billing/PlanCard.tsx`
- **Depends on**: T057, T013 (backend), T023 (backend)
- **Acceptance criteria**: Client-generated `Idempotency-Key` (UUID) sent on the create-subscription
  call; successful call redirects the browser to `checkoutUrl`; manual QA against Stripe test mode
  completes a real test-card checkout.
- **Complexity**: M

### T061 — Checkout success/cancel pages
- **Goal**: Land the user somewhere sensible after Stripe Checkout.
- **Files**: `frontend/src/pages/CheckoutPage.tsx` (or split success/cancel components)
- **Depends on**: T060
- **Acceptance criteria**: Success page polls/fetches `GET /subscriptions/me` until status is
  `ACTIVE` (handles the brief async window before the webhook lands) or shows a "processing" state
  with a sensible timeout/retry message.
- **Complexity**: M

### T062 — Dashboard page (current subscription + status banner)
- **Goal**: At-a-glance subscription health, reflecting every status from ARCHITECTURE.md §5.
- **Files**: `frontend/src/pages/DashboardPage.tsx`, `frontend/src/components/billing/
  SubscriptionStatusBanner.tsx`
- **Depends on**: T061
- **Acceptance criteria**: Each of the 6 subscription statuses renders a visually distinct,
  correctly worded banner (e.g., `PAST_DUE` shows days remaining in grace period); component-level
  test (Vitest + RTL) covers all 6 states via mocked API responses.
- **Complexity**: M

### T063 — Subscription management page: upgrade/downgrade with live proration preview
- **Goal**: The centerpiece UI for the resume-driving proration feature.
- **Files**: `frontend/src/pages/SubscriptionManagementPage.tsx`,
  `frontend/src/components/billing/ProrationPreview.tsx`
- **Depends on**: T062, T036 (backend), T037 (backend)
- **Acceptance criteria**: Selecting a new plan calls `preview-change` and renders the credit/debit
  line items and net amount clearly; confirming calls `change-plan` with a fresh idempotency key;
  loading/error states handled; manual QA against a real test-mode subscription confirms the UI
  numbers match what Stripe's dashboard shows for the resulting invoice.
- **Complexity**: L

### T064 — Cancellation flow UI
- **Goal**: Let the customer cancel with a clear choice between immediate/at-period-end.
- **Files**: `SubscriptionManagementPage.tsx` (extend), `frontend/src/components/billing/
  CancelSubscriptionModal.tsx`
- **Depends on**: T063, T048 (backend)
- **Acceptance criteria**: Confirmation modal before cancellation; correct idempotency key usage;
  dashboard reflects the new status immediately after success.
- **Complexity**: S

### T065 — Billing history page (invoices)
- **Goal**: Customer-facing invoice list and detail view.
- **Files**: `frontend/src/pages/BillingHistoryPage.tsx`, `frontend/src/components/billing/
  InvoiceTable.tsx`, `frontend/src/api/invoiceApi.ts`
- **Depends on**: T033 (backend), T062
- **Acceptance criteria**: Paginated table; clicking an invoice shows line items (including
  proration credit/debit lines with clear labels); money values formatted via a single
  `formatMoney` utility.
- **Complexity**: M

### T066 — Admin dashboard (customers, subscriptions, audit log viewer)
- **Goal**: Minimal but functional admin surface.
- **Files**: `frontend/src/pages/admin/AdminDashboardPage.tsx`, `frontend/src/components/admin/
  AdminSubscriptionTable.tsx`, `AdminCustomerTable.tsx`, `frontend/src/api/adminApi.ts`
- **Depends on**: T050 (backend), T051 (backend), T065
- **Acceptance criteria**: Admin-only route (redirect/hide for non-admins); force-cancel and
  manual-retry actions callable from the table with confirmation prompts; audit log viewer supports
  basic filtering by entity type.
- **Complexity**: L

---

## Phase 15 — Final Hardening & Delivery

### T067 — Stripe CLI manual webhook verification pass
- **Goal**: Confirm real Stripe signature verification and event handling end-to-end, not just
  against fixtures (per TESTING.md §5 point 3).
- **Files**: none (manual verification task); update `README.md` with the verified steps
- **Depends on**: T066
- **Acceptance criteria**: `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe`
  combined with `stripe trigger checkout.session.completed` (and the other four event types) each
  produce the expected local state change, confirmed by manual inspection; steps documented in
  README for reviewers to reproduce.
- **Complexity**: M

### T068 — Full README with architecture summary, setup instructions, and resume-feature call-outs
- **Goal**: Make the portfolio project legible to a reviewer in under 5 minutes.
- **Files**: `README.md`
- **Depends on**: T067
- **Acceptance criteria**: README includes: one-paragraph overview, architecture diagram (embed or
  link to ARCHITECTURE.md's mermaid diagrams), setup instructions (`docker compose up` +
  `.env` config + Stripe test keys needed), and an explicit section mapping each of the four resume
  bullet points to the specific code/tests that implement it (file paths), so a reviewer can jump
  straight to the interesting code.
- **Complexity**: S

### T069 — Final full-suite pass: backend tests, frontend tests, CI green, coverage gate green
- **Goal**: Definition-of-done check before calling the project complete.
- **Files**: none (verification task)
- **Depends on**: T068
- **Acceptance criteria**: `mvn verify` green locally and in CI; `npm run build && npm test` green;
  JaCoCo gate (T053) passes; `docker compose up` from a clean clone works without manual
  intervention beyond populating `.env` from `.env.example`.
- **Complexity**: S
