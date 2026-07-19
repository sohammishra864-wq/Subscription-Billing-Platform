# EPICS — Subscription Billing Platform

Epics map to GitHub Milestones. Each epic groups related tasks (from TASKS.md) into a shippable unit.

---

## Epic 1: Foundation & Infrastructure
**Goal**: Repo, CI, Docker, database — everything needed before writing business logic.
**Tasks**: T001–T005
**Deliverable**: `docker compose up` boots Postgres + Spring Boot, CI runs on push.
**PRs**: 3–5

---

## Epic 2: Identity & Access Control
**Goal**: Users can register, login, get JWTs, and roles are enforced.
**Tasks**: T006–T011
**Deliverable**: Full auth flow working end-to-end, consistent error responses.
**PRs**: 4–6

---

## Epic 3: Plan Catalog
**Goal**: Admins manage plans, customers browse them.
**Tasks**: T012–T014
**Deliverable**: CRUD plans (admin), public listing, dev seed data.
**PRs**: 2–3

---

## Epic 4: Stripe Integration & Customer Bootstrap
**Goal**: Internal users linked to Stripe Customers, SDK wrapped for testability.
**Tasks**: T015–T016
**Deliverable**: `CustomerService.getOrCreateForUser()` working with Stripe test mode.
**PRs**: 2

---

## Epic 5: Idempotency Engine
**Goal**: Bullet-proof duplicate-request protection, independent of any specific endpoint.
**Tasks**: T017–T020
**Deliverable**: `@IdempotentEndpoint` annotation working with concurrency tests passing.
**PRs**: 3–4

---

## Epic 6: Subscription Lifecycle
**Goal**: Create subscriptions via Stripe Checkout, track state machine transitions.
**Tasks**: T021–T024
**Deliverable**: POST /subscriptions → Checkout → INCOMPLETE state, GET /subscriptions/me.
**PRs**: 3–4

---

## Epic 7: Webhook Engine
**Goal**: Receive, verify, deduplicate, and dispatch Stripe webhooks.
**Tasks**: T025–T030
**Deliverable**: All 5 webhook event types handled, dedup proven, signature verified.
**PRs**: 4–6

---

## Epic 8: Invoicing
**Goal**: Invoice records synced from Stripe, customer-facing history.
**Tasks**: T031–T033
**Deliverable**: Invoice table populated by webhooks, paginated history endpoint.
**PRs**: 2–3

---

## Epic 9: Proration Engine
**Goal**: The resume-driving feature — prorated upgrades/downgrades with full audit trail.
**Tasks**: T034–T037
**Deliverable**: Preview + apply plan changes, ledger entries, edge cases tested.
**PRs**: 3–4

---

## Epic 10: Dunning Workflow
**Goal**: Failed payment recovery — retry scheduling, notifications, access restriction.
**Tasks**: T038–T045
**Deliverable**: Full dunning loop: fail → retry → recover/restrict, both paths tested.
**PRs**: 5–7

---

## Epic 11: Background Jobs & Reconciliation
**Goal**: Self-healing jobs for expired checkouts, stale subscriptions, key cleanup.
**Tasks**: T046–T047
**Deliverable**: Automated cleanup and Stripe reconciliation running on schedule.
**PRs**: 2

---

## Epic 12: Cancellation & Admin Tooling
**Goal**: Customer cancellation + admin superpowers + audit trail.
**Tasks**: T048–T051
**Deliverable**: Cancel flow, admin dashboard endpoints, full audit log.
**PRs**: 3–4

---

## Epic 13: Backend Hardening
**Goal**: Concurrency tests, coverage gates, API docs.
**Tasks**: T052–T054
**Deliverable**: CI enforces 80%+ coverage, concurrency suite green, Swagger live.
**PRs**: 2–3

---

## Epic 14: Frontend Core
**Goal**: React app scaffold, auth, Docker integration, CI.
**Tasks**: T055–T059
**Deliverable**: Login/register working against real backend in Docker Compose.
**PRs**: 4–5

---

## Epic 15: Frontend Billing UX
**Goal**: The customer-facing billing experience — plans, checkout, dashboard, invoices.
**Tasks**: T060–T066
**Deliverable**: Full billing UI: subscribe, upgrade/downgrade with preview, invoice history, admin panel.
**PRs**: 5–7

---

## Epic 16: Final Hardening & Delivery
**Goal**: Manual Stripe verification, README polish, everything green.
**Tasks**: T067–T069
**Deliverable**: Ship-ready portfolio project.
**PRs**: 2–3

---

## Summary

| # | Epic | Tasks | Est. PRs |
|---|---|---|---|
| 1 | Foundation & Infra | T001–T005 | 3–5 |
| 2 | Identity & Access | T006–T011 | 4–6 |
| 3 | Plan Catalog | T012–T014 | 2–3 |
| 4 | Stripe & Customer | T015–T016 | 2 |
| 5 | Idempotency | T017–T020 | 3–4 |
| 6 | Subscription Lifecycle | T021–T024 | 3–4 |
| 7 | Webhook Engine | T025–T030 | 4–6 |
| 8 | Invoicing | T031–T033 | 2–3 |
| 9 | Proration Engine | T034–T037 | 3–4 |
| 10 | Dunning Workflow | T038–T045 | 5–7 |
| 11 | Background Jobs | T046–T047 | 2 |
| 12 | Cancellation & Admin | T048–T051 | 3–4 |
| 13 | Backend Hardening | T052–T054 | 2–3 |
| 14 | Frontend Core | T055–T059 | 4–5 |
| 15 | Frontend Billing UX | T060–T066 | 5–7 |
| 16 | Final Delivery | T067–T069 | 2–3 |
| | **Total** | **69 tasks** | **~50–70 PRs** |

Each PR merged = progress toward Pull Shark tiers. Every commit with `Co-authored-by:` = Pair Extraordinaire progress.
