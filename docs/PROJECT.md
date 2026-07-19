# PROJECT.md — Subscription Billing Platform

## 1. Project Overview

The Subscription Billing Platform (SBP) is a production-grade, multi-tenant-ready SaaS billing engine that
manages the full lifecycle of subscriptions: plan selection, checkout, recurring billing, mid-cycle
upgrades/downgrades with proration, invoice generation, failed-payment recovery (dunning), and
access control based on subscription health.

It is built to resemble the internal billing infrastructure a payments company (e.g., Stripe, Chargebee,
Recurly) would build for itself — not a tutorial CRUD app. The defining engineering challenges are:

- **Correctness under concurrency and duplication** (idempotent APIs, deduplicated webhooks).
- **Financial correctness** (proration math, invoice line items, credit/debit ledger entries that always
  net to zero, no silent money loss/gain).
- **Resilience to partial failure** (Stripe is a third-party system; webhooks can be delayed, retried,
  or arrive out of order; the local DB and Stripe can transiently disagree and must reconcile).
- **Operational realism** (dunning, retries with backoff, background jobs, audit trails).

The system owns subscription/customer/plan/invoice state in PostgreSQL as the source of truth for
**business logic and access control**, while Stripe remains the source of truth for **payment
method storage and actual money movement**. Webhooks are the synchronization mechanism between
the two, and the architecture is built around the assumption that this synchronization is
eventually consistent, not instantaneous.

## 2. Business Requirements

- BR1: Customers can subscribe to one of several tiered plans (e.g., Free, Basic, Pro, Enterprise),
  each with a monthly and/or annual price.
- BR2: Customers can upgrade or downgrade their plan at any point in the billing cycle, and must be
  charged/credited fairly for the unused/extra time (proration).
- BR3: Payments are processed via Stripe; the platform must never store raw card data (PCI scope
  reduction via Stripe Elements/Checkout + Payment Methods API).
- BR4: If a recurring payment fails, the system must retry it automatically over a bounded window,
  notify the customer, and eventually restrict access if payment cannot be recovered (dunning).
- BR5: Duplicate requests (e.g., a customer double-clicking "Subscribe", or a flaky mobile network
  causing a retry) must never result in duplicate subscriptions or duplicate charges.
- BR6: Stripe webhook events must be processed safely even if Stripe redelivers the same event
  multiple times (Stripe explicitly documents that this can happen).
- BR7: All billing-relevant state changes must be auditable — every invoice, adjustment, and status
  transition must be traceable to a cause (webhook event, admin action, scheduled job).
- BR8: Admins must be able to view all customers, subscriptions, invoices, and manually intervene
  (e.g., issue a refund/credit, force-cancel a subscription).
- BR9: The system must support plan catalog changes (adding new plans, archiving old ones) without
  breaking existing subscribers on legacy plans.

## 3. Functional Requirements

### 3.1 Authentication & Authorization
- FR1: Users register with email/password; passwords are hashed with BCrypt.
- FR2: Users authenticate via JWT (access token + refresh token pair).
- FR3: Role-based access control: `CUSTOMER` and `ADMIN` roles at minimum.
- FR4: Protected endpoints reject requests with missing/expired/invalid JWTs with 401.
- FR5: Admin-only endpoints reject non-admin users with 403.

### 3.2 Plan & Catalog Management
- FR6: Admins can create, update (price/description), and archive plans.
- FR7: Customers can list all active plans and their pricing.
- FR8: Plans have a billing interval (`MONTHLY`, `ANNUAL`), a price in minor currency units (cents),
  and a currency code.

### 3.3 Subscription Lifecycle
- FR9: A customer can create a new subscription for a plan, providing an idempotency key.
- FR10: A customer can upgrade/downgrade their active subscription to a different plan.
- FR11: A customer can cancel a subscription (immediately or at period end).
- FR12: The system computes and applies proration credits/debits on upgrade/downgrade.
- FR13: Subscriptions have a well-defined state machine (see ARCHITECTURE.md §5).
- FR14: Subscription status changes are driven by both direct API actions and asynchronous Stripe
  webhook events, and both paths must converge to a consistent final state.

### 3.4 Checkout & Payment
- FR15: The system integrates Stripe Checkout Session (or Payment Intents + Elements) to collect
  payment method details without touching raw card data.
- FR16: On successful initial payment, a subscription is activated.
- FR17: On failed initial payment, the subscription is not created (or created in an `INCOMPLETE`
  state that expires).

### 3.5 Idempotency
- FR18: All mutating billing endpoints (create subscription, upgrade, downgrade, cancel) require an
  `Idempotency-Key` header.
- FR19: Replaying a request with the same idempotency key and same request body returns the original
  stored response instead of re-executing the operation.
- FR20: Replaying a request with the same idempotency key but a **different** request body returns a
  409 Conflict.
- FR21: Idempotency keys expire after a configurable retention window (e.g., 24 hours) and are
  purged by a background job.

### 3.6 Webhooks
- FR22: The system exposes a Stripe webhook endpoint that verifies the Stripe-Signature header.
- FR23: Every received webhook event is persisted (event ID, type, payload, received_at, processed_at,
  status) before processing, so redelivery can be detected.
- FR24: Processing a webhook event whose ID has already been marked `PROCESSED` is a no-op that
  still returns 200 OK to Stripe.
- FR25: Webhook handlers are idempotent at the business-logic level as a second line of defense
  (not just event-ID deduplication).

### 3.7 Invoicing
- FR26: Every billing cycle (and every proration event) generates an invoice with line items.
- FR27: Invoices have statuses: `DRAFT`, `OPEN`, `PAID`, `UNCOLLECTIBLE`, `VOID`.
- FR28: Customers can view their invoice history and download/view invoice details.

### 3.8 Dunning
- FR29: When a recurring payment fails, the system schedules a bounded sequence of retries with
  exponential backoff (configurable, e.g., attempts at day 1, day 3, day 5, day 7).
- FR30: Each retry attempt result (success/failure) is recorded.
- FR31: The customer receives a notification (email, simulated via logging/console or a real
  provider) after each failed attempt and before final suspension.
- FR32: If all retries are exhausted, the subscription transitions to `PAST_DUE` → `UNPAID`/`CANCELED`
  and access is restricted.
- FR33: If payment succeeds at any retry, the subscription returns to `ACTIVE` and access is restored.

### 3.9 Access Control
- FR34: API endpoints that gate feature access must check the customer's current subscription status
  and reject/allow access accordingly (e.g., a `PAST_DUE` customer past the grace period is blocked
  from premium endpoints).

### 3.10 Admin Operations
- FR35: Admins can view all subscriptions/invoices/customers with filtering and pagination.
- FR36: Admins can manually trigger a retry, issue a manual refund/credit, or force-cancel a
  subscription.

## 4. Non-Functional Requirements

- NFR1 **Correctness**: Financial calculations use integer minor-unit arithmetic (cents), never
  floating point, to avoid rounding errors.
- NFR2 **Idempotency**: No mutating billing operation may be safely retried without idempotency
  protection.
- NFR3 **Consistency**: The system must reconcile local state with Stripe's state; webhook processing
  must be resilient to out-of-order delivery.
- NFR4 **Auditability**: Every state transition is logged with enough context to reconstruct "why."
- NFR5 **Security**: JWT-based auth, BCrypt password hashing, Stripe webhook signature verification,
  no PCI-sensitive data stored, secrets via environment variables, HTTPS assumed in production.
- NFR6 **Testability**: Business logic (proration, dunning scheduling, idempotency) is unit-testable
  in isolation from Stripe and the web layer.
- NFR7 **Observability**: Structured logging with correlation/request IDs; key billing events are
  logged at INFO, failures at WARN/ERROR.
- NFR8 **Performance**: API p95 latency target < 300ms for non-Stripe-calling endpoints under
  local/dev load; database queries indexed appropriately.
- NFR9 **Portability**: Fully containerized via Docker Compose; runs identically on any Docker host.
- NFR10 **Maintainability**: Layered architecture with clear separation of concerns (see
  ARCHITECTURE.md); consistent coding conventions (see §12).
- NFR11 **Extensibility**: New plan types, new payment providers (in theory), and new notification
  channels should be addable without rewriting core domain logic (ports/adapters at the boundaries).

## 5. User Roles

| Role | Description | Example Capabilities |
|---|---|---|
| `CUSTOMER` | A registered end user who subscribes to plans | Create/upgrade/downgrade/cancel own subscription, view own invoices, manage own payment method |
| `ADMIN` | Internal operator | View all customers/subscriptions/invoices, manage plan catalog, manually intervene on billing issues, view audit logs |

Future extension (documented but not required for MVP): `SUPPORT` role with read-only access to
customer billing data for support/debugging purposes.

## 6. Core Features (Resume-Driving Features)

These four features are the centerpiece of the project and must be implemented with production-level
rigor, not simplified stand-ins:

1. **Prorated billing for upgrades/downgrades** — daily-rate proration engine producing explicit
   credit/debit line items, documented and tested against edge cases (leap years, timezone
   boundaries, same-day plan switches, downgrade-then-upgrade in same cycle).
2. **Idempotent subscription creation** — `Idempotency-Key` header, persisted key/request-hash/response
   table, safe concurrent replay handling (unique constraint + row locking).
3. **Stripe webhook handlers with deduplication** — signature verification, persisted event log,
   dedup by Stripe event ID, idempotent handler logic per event type.
4. **Full dunning workflow** — scheduled retry engine, status transitions, notification hooks, access
   restriction, recovery path.

## 7. Tech Stack

### Backend
- Java 21 (records, pattern matching, virtual threads where beneficial for I/O-bound Stripe calls)
- Spring Boot 3.x (Web, Security, Data JPA, Validation, Actuator)
- PostgreSQL 15+
- Spring Security 6 + JWT (jjwt or Nimbus JOSE)
- Flyway for schema migrations (source of truth for schema, no `ddl-auto: update` in any environment)
- Maven (multi-module optional; single module acceptable for portfolio scope, documented in §8)
- Stripe Java SDK
- Spring Scheduler (or Quartz if we want persistent, clusterable job scheduling — see trade-off
  discussion in ARCHITECTURE.md) for dunning retries and idempotency-key cleanup
- Resend/SMTP (or a logging-based `NotificationSender` interface with a console/log implementation
  for portfolio purposes, swappable for a real provider)
- JUnit 5, Mockito, Testcontainers (Postgres), WireMock or Stripe's test-mode sandbox for webhook tests

### Frontend
- React 18 + TypeScript
- Tailwind CSS
- React Query (server state, caching, retries)
- Axios (HTTP client with interceptors for JWT attach/refresh)
- React Router
- React Hook Form + Zod (form validation) — recommended, not mandatory
- Stripe.js / React Stripe.js for Checkout/Elements integration

### Infrastructure
- Docker, Docker Compose (local dev: postgres, backend, frontend, optionally a Stripe CLI container
  for webhook forwarding)
- GitHub Actions CI (build + test backend, build + lint frontend, optionally build Docker images)
- Flyway migrations run automatically on backend startup in dev; run explicitly as a CI/CD step in
  the documented production flow

## 8. Complete Folder Structure

```
subscription-billing-platform/
├── backend/
│   ├── pom.xml
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/sbp/
│   │   │   │   ├── SbpApplication.java
│   │   │   │   ├── config/
│   │   │   │   │   ├── SecurityConfig.java
│   │   │   │   │   ├── JwtConfig.java
│   │   │   │   │   ├── StripeConfig.java
│   │   │   │   │   ├── SchedulingConfig.java
│   │   │   │   │   ├── OpenApiConfig.java
│   │   │   │   │   └── WebConfig.java (CORS, etc.)
│   │   │   │   ├── security/
│   │   │   │   │   ├── JwtAuthenticationFilter.java
│   │   │   │   │   ├── JwtTokenProvider.java
│   │   │   │   │   ├── CustomUserDetailsService.java
│   │   │   │   │   └── SecurityUser.java
│   │   │   │   ├── auth/
│   │   │   │   │   ├── AuthController.java
│   │   │   │   │   ├── AuthService.java
│   │   │   │   │   └── dto/ (RegisterRequest, LoginRequest, TokenResponse, RefreshRequest)
│   │   │   │   ├── user/
│   │   │   │   │   ├── User.java
│   │   │   │   │   ├── UserRepository.java
│   │   │   │   │   ├── UserService.java
│   │   │   │   │   └── Role.java (enum)
│   │   │   │   ├── plan/
│   │   │   │   │   ├── Plan.java
│   │   │   │   │   ├── PlanRepository.java
│   │   │   │   │   ├── PlanService.java
│   │   │   │   │   ├── PlanController.java
│   │   │   │   │   └── dto/
│   │   │   │   ├── customer/
│   │   │   │   │   ├── Customer.java (maps User <-> Stripe Customer)
│   │   │   │   │   ├── CustomerRepository.java
│   │   │   │   │   └── CustomerService.java
│   │   │   │   ├── subscription/
│   │   │   │   │   ├── Subscription.java
│   │   │   │   │   ├── SubscriptionStatus.java (enum)
│   │   │   │   │   ├── SubscriptionRepository.java
│   │   │   │   │   ├── SubscriptionService.java
│   │   │   │   │   ├── SubscriptionController.java
│   │   │   │   │   ├── SubscriptionStateMachine.java
│   │   │   │   │   └── dto/
│   │   │   │   ├── proration/
│   │   │   │   │   ├── ProrationCalculator.java
│   │   │   │   │   ├── ProrationResult.java
│   │   │   │   │   └── ProrationLineItem.java
│   │   │   │   ├── billing/
│   │   │   │   │   ├── invoice/
│   │   │   │   │   │   ├── Invoice.java
│   │   │   │   │   │   ├── InvoiceLineItem.java
│   │   │   │   │   │   ├── InvoiceStatus.java (enum)
│   │   │   │   │   │   ├── InvoiceRepository.java
│   │   │   │   │   │   ├── InvoiceService.java
│   │   │   │   │   │   └── InvoiceController.java
│   │   │   │   │   └── ledger/
│   │   │   │   │       ├── LedgerEntry.java
│   │   │   │   │       ├── LedgerEntryType.java (enum)
│   │   │   │   │       └── LedgerRepository.java
│   │   │   │   ├── payment/
│   │   │   │   │   ├── PaymentAttempt.java
│   │   │   │   │   ├── PaymentAttemptRepository.java
│   │   │   │   │   ├── PaymentService.java (wraps Stripe PaymentIntent calls)
│   │   │   │   │   └── StripeCheckoutService.java
│   │   │   │   ├── webhook/
│   │   │   │   │   ├── StripeWebhookController.java
│   │   │   │   │   ├── WebhookEvent.java
│   │   │   │   │   ├── WebhookEventRepository.java
│   │   │   │   │   ├── WebhookEventStatus.java (enum)
│   │   │   │   │   ├── WebhookDispatcher.java
│   │   │   │   │   └── handlers/
│   │   │   │   │       ├── WebhookEventHandler.java (interface)
│   │   │   │   │       ├── CheckoutSessionCompletedHandler.java
│   │   │   │   │       ├── InvoicePaymentSucceededHandler.java
│   │   │   │   │       ├── InvoicePaymentFailedHandler.java
│   │   │   │   │       ├── CustomerSubscriptionUpdatedHandler.java
│   │   │   │   │       └── CustomerSubscriptionDeletedHandler.java
│   │   │   │   ├── idempotency/
│   │   │   │   │   ├── IdempotencyKey.java
│   │   │   │   │   ├── IdempotencyKeyRepository.java
│   │   │   │   │   ├── IdempotencyService.java
│   │   │   │   │   └── IdempotentRequestInterceptor.java (or aspect/filter)
│   │   │   │   ├── dunning/
│   │   │   │   │   ├── DunningAttempt.java
│   │   │   │   │   ├── DunningAttemptRepository.java
│   │   │   │   │   ├── DunningPolicy.java
│   │   │   │   │   ├── DunningScheduler.java
│   │   │   │   │   ├── DunningService.java
│   │   │   │   │   └── AccessRestrictionService.java
│   │   │   │   ├── notification/
│   │   │   │   │   ├── NotificationSender.java (interface)
│   │   │   │   │   ├── LoggingNotificationSender.java
│   │   │   │   │   ├── EmailNotificationSender.java (optional real impl)
│   │   │   │   │   └── NotificationTemplate.java
│   │   │   │   ├── admin/
│   │   │   │   │   ├── AdminController.java
│   │   │   │   │   └── AdminService.java
│   │   │   │   ├── audit/
│   │   │   │   │   ├── AuditLog.java
│   │   │   │   │   ├── AuditLogRepository.java
│   │   │   │   │   └── AuditLogService.java
│   │   │   │   ├── common/
│   │   │   │   │   ├── exception/ (GlobalExceptionHandler, custom exceptions)
│   │   │   │   │   ├── money/ (Money value type, currency utils)
│   │   │   │   │   ├── ApiResponse.java
│   │   │   │   │   └── PageResponse.java
│   │   │   │   └── scheduling/
│   │   │   │       ├── IdempotencyKeyCleanupJob.java
│   │   │   │       └── SubscriptionRenewalReconciliationJob.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       ├── application-prod.yml
│   │   │       ├── application-test.yml
│   │   │       └── db/migration/ (Flyway: V1__init.sql, V2__..., etc.)
│   │   └── test/
│   │       ├── java/com/sbp/
│   │       │   ├── proration/ProrationCalculatorTest.java
│   │       │   ├── subscription/SubscriptionServiceTest.java
│   │       │   ├── idempotency/IdempotencyServiceTest.java
│   │       │   ├── webhook/WebhookDeduplicationTest.java
│   │       │   ├── dunning/DunningServiceTest.java
│   │       │   ├── integration/ (Testcontainers-based full-stack tests)
│   │       │   └── util/TestFixtures.java
│   │       └── resources/application-test.yml
│   └── Dockerfile
├── frontend/
│   ├── package.json
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   ├── vite.config.ts (or CRA config, Vite recommended — see PROJECT.md §13 assumptions)
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── api/
│   │   │   ├── axiosClient.ts
│   │   │   ├── authApi.ts
│   │   │   ├── planApi.ts
│   │   │   ├── subscriptionApi.ts
│   │   │   ├── invoiceApi.ts
│   │   │   └── adminApi.ts
│   │   ├── hooks/ (React Query hooks: useSubscription, usePlans, useInvoices, ...)
│   │   ├── context/AuthContext.tsx
│   │   ├── components/
│   │   │   ├── layout/ (Navbar, Sidebar, ProtectedRoute)
│   │   │   ├── billing/ (PlanCard, ProrationPreview, InvoiceTable, CheckoutForm)
│   │   │   ├── admin/ (AdminSubscriptionTable, AdminCustomerTable)
│   │   │   └── common/ (Button, Modal, Badge, Spinner)
│   │   ├── pages/
│   │   │   ├── LoginPage.tsx
│   │   │   ├── RegisterPage.tsx
│   │   │   ├── PlansPage.tsx
│   │   │   ├── CheckoutPage.tsx
│   │   │   ├── DashboardPage.tsx
│   │   │   ├── BillingHistoryPage.tsx
│   │   │   ├── SubscriptionManagementPage.tsx
│   │   │   └── admin/AdminDashboardPage.tsx
│   │   ├── types/ (TS interfaces mirroring backend DTOs)
│   │   └── utils/ (formatMoney, dateUtils)
│   └── Dockerfile
├── infra/
│   ├── docker-compose.yml
│   ├── docker-compose.override.yml (dev-only, e.g., Stripe CLI listener)
│   └── postgres/init/ (optional seed scripts)
├── .github/
│   └── workflows/
│       ├── backend-ci.yml
│       └── frontend-ci.yml
├── docs/
│   ├── PROJECT.md
│   ├── ARCHITECTURE.md
│   ├── IMPLEMENTATION.md
│   ├── TESTING.md
│   └── TASKS.md
├── .env.example
└── README.md
```

## 9. Project Milestones

| Milestone | Description |
|---|---|
| M0 — Foundations | Repo scaffolding, Docker Compose, Postgres, Flyway baseline, Spring Boot skeleton, CI pipeline skeleton |
| M1 — AuthN/AuthZ | User registration/login, JWT issuance/refresh, role-based route protection |
| M2 — Plan Catalog | Plan entity/CRUD (admin), public plan listing endpoint |
| M3 — Stripe Customer & Checkout | Stripe customer creation, Checkout Session creation, successful-payment webhook handling |
| M4 — Subscription Core | Subscription entity, state machine, creation flow, idempotency key infrastructure |
| M5 — Webhook Sync Engine | Webhook event persistence, signature verification, dedup, handler dispatch for core event types |
| M6 — Proration Engine | Upgrade/downgrade flow, proration calculator, credit/debit ledger, adjustment invoices |
| M7 — Invoicing | Full invoice generation for recurring cycles and prorations, invoice history API |
| M8 — Dunning Workflow | Retry scheduler, notification hooks, status transitions, access restriction middleware |
| M9 — Admin Tooling | Admin endpoints, manual intervention actions, audit log viewer |
| M10 — Frontend Core | Auth pages, plan listing, checkout integration, dashboard |
| M11 — Frontend Billing UX | Subscription management UI, proration preview, invoice history, dunning status banners |
| M12 — Hardening | Testcontainers integration tests, concurrency tests, load-light performance pass, README polish |

## 10. MVP vs Advanced Features

### MVP (must exist for the project to be "done")
- Auth (register/login/JWT), roles (CUSTOMER/ADMIN)
- Plan catalog (list, admin CRUD)
- Subscription create via Stripe Checkout, with idempotency key support
- Webhook handling for `checkout.session.completed`, `invoice.payment_succeeded`,
  `invoice.payment_failed`, `customer.subscription.updated`, `customer.subscription.deleted`,
  all deduplicated
- Upgrade/downgrade with proration calculation and adjustment invoice
- Basic dunning: 3-attempt retry schedule, status transitions, access restriction
- Invoice history view
- Admin view of customers/subscriptions
- Dockerized full stack, CI running backend + frontend tests

### Advanced (nice-to-have, demonstrates extra depth if time allows)
- Annual plans with proration across annual↔monthly switches
- Coupons/discounts
- Multi-currency support
- Usage-based billing add-on (metered component on top of flat subscription)
- Customer self-service payment method update flow (Stripe Customer Portal integration)
- Email notifications via a real provider (Resend/SendGrid) instead of logging sender
- Webhook replay/dead-letter admin UI
- Rate limiting on public endpoints
- OpenTelemetry tracing across the webhook → DB → notification path
- Soft-delete/archival strategy for old invoices

## 11. Coding Conventions

### Backend (Java/Spring)
- Package-by-feature (as shown in §8), not package-by-layer, to keep each domain concept
  (subscription, webhook, dunning) cohesive.
- Constructor injection only; no field injection (`@Autowired` on fields is disallowed).
- Controllers are thin: validate input (via `@Valid` DTOs), delegate to a service, map to response DTO.
  No business logic in controllers.
- Services own transaction boundaries (`@Transactional` at the service method level, never at the
  controller or repository level).
- Entities never leak directly through the API; always map to DTOs (MapStruct recommended, manual
  mapping acceptable for portfolio scope).
- Money is never represented as `double`/`float`. Use `long` cents (or `BigDecimal` with fixed scale
  and `RoundingMode.HALF_EVEN` if a `Money` value type is introduced) — decision documented in
  ARCHITECTURE.md.
- All timestamps stored in UTC (`Instant` / `TIMESTAMPTZ`); conversion to local time is a
  presentation-layer concern only.
- Custom exceptions extend a common `SbpException` hierarchy; a `@RestControllerAdvice`
  `GlobalExceptionHandler` maps them to consistent JSON error responses.
- Every public service method that mutates state has a corresponding audit log entry or is
  explicitly documented as exempt.
- Logging: use SLF4J with parameterized messages (`log.info("Subscription {} activated", id)`),
  never string concatenation; never log secrets, JWTs, or full card/payment details.
- Naming: `XxxController`, `XxxService`, `XxxRepository`, `XxxMapper`; DTOs suffixed `Request`/`Response`.

### Frontend (React/TS)
- Functional components + hooks only; no class components.
- Strict TypeScript (`strict: true`); no `any` except at well-justified third-party boundaries.
- Server state lives in React Query; local UI state in `useState`/`useReducer`; no server data
  duplicated into global client state stores.
- API calls isolated to `src/api/*` modules; components never call `axios` directly.
- Tailwind utility classes composed via a small `cn()` helper (clsx/tailwind-merge) for conditional
  classNames; no inline `style=` unless dynamic values require it.
- Money values always formatted through a single `formatMoney` utility (cents → localized currency
  string), never ad hoc `toFixed(2)` scattered across components.

### Git / Process
- Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`).
- Each TASKS.md task should correspond to one or a small number of focused commits.
- No task is "done" until its acceptance criteria in TASKS.md are met and (where applicable) tests
  pass in CI.

## 12. Assumptions

These are explicit assumptions made to keep the project scoped and unambiguous for implementation:

1. Single currency (USD) for MVP; multi-currency is an advanced/optional extension.
2. Stripe **test mode** is used throughout development; no real charges occur.
3. Stripe is treated as the payment processor and the authoritative source for payment
   success/failure and payment-method state; PostgreSQL is authoritative for business/domain state
   (subscription status as understood by *this application's* access control), reconciled via webhooks.
4. Billing model: flat-rate recurring subscriptions (Stripe "Prices" of type `recurring`), not
   usage-based, for MVP.
5. One active subscription per customer for MVP (no multi-subscription-per-customer support), to
   keep the state machine and proration logic tractable; documented as a clean extension point.
6. Notifications (dunning emails) are implemented behind a `NotificationSender` interface; the
   default implementation logs to console/log file, satisfying the "sends notifications" requirement
   without requiring a real transactional email provider account. A real provider integration is
   listed as an advanced feature.
7. The dunning retry schedule (day 1/3/5/7, 3–4 attempts) is configurable via `application.yml`,
   not hardcoded, so it can be tuned/demonstrated without a code change.
8. Background job scheduling uses Spring's `@Scheduled` for MVP; Quartz (DB-persisted, cluster-safe
   jobs) is discussed in ARCHITECTURE.md as the production-scale alternative given this is a
   single-instance portfolio deployment.
9. Frontend build tool: Vite (faster DX than CRA; CRA is legacy/deprecated as of 2025). If the
   implementer prefers CRA/Next.js, folder structure adapts accordingly but the component/API
   organization stays the same.
10. Authentication is self-hosted JWT (not an external IdP like Auth0/Cognito) to keep the "built by
    an engineer" narrative focused on billing engineering, not auth-vendor integration.
11. Proration policy: **daily proration** (unused days credited/charged at a per-day rate derived
    from the plan price and the number of days in the current billing period), matching Stripe's own
    default proration behavior — chosen over second-level proration for explainability in a demo/interview
    context, with the exact algorithm specified in IMPLEMENTATION.md.
