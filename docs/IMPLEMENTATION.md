# IMPLEMENTATION.md — How The System Works

This document explains, mechanism by mechanism, how each core behavior is implemented. It is the
companion to ARCHITECTURE.md (which explains *what* the pieces are) — this explains *how they
operate*, with pseudocode for the non-obvious algorithms. TASKS.md breaks this into executable steps.

## 1. Phase-by-Phase Implementation Plan

This mirrors PROJECT.md §9 milestones but at implementation granularity:

**Phase 1 — Skeleton & Infra**: Docker Compose (Postgres + backend + frontend), Flyway baseline
migration, Spring Boot health-check endpoint, CI pipeline that runs `mvn test` and `npm test` on
every push.

**Phase 2 — Identity**: `users` table, registration/login, JWT issue/validate, `refresh_tokens`,
role-gated route smoke test (an endpoint only ADMIN can hit).

**Phase 3 — Catalog**: `plans` table + admin CRUD + public list endpoint; seed 3–4 plans matching
Stripe test-mode Prices created via the Stripe Dashboard (or a seed script using the Stripe API).

**Phase 4 — Stripe Customer Bootstrap**: `customers` table; on first subscription attempt (or on
registration), create a Stripe Customer and persist `stripe_customer_id`.

**Phase 5 — Idempotency Infrastructure**: `idempotency_keys` table + `IdempotencyService` +
enforcement mechanism (interceptor/aspect), built and unit-tested **before** it's wired into the
subscription endpoints, so it can be tested in isolation.

**Phase 6 — Subscription Creation & Checkout**: `subscriptions` table, state machine,
`POST /subscriptions` → Stripe Checkout Session, wired through idempotency layer.

**Phase 7 — Webhook Engine**: `webhook_events` table, signature verification, dispatcher, handlers
for `checkout.session.completed`, `invoice.payment_succeeded`, `invoice.payment_failed`,
`customer.subscription.updated`, `customer.subscription.deleted`.

**Phase 8 — Invoicing**: `invoices` + `invoice_line_items` tables; recurring-cycle invoice creation
driven by `invoice.payment_succeeded`/`invoice.payment_failed` webhooks (mirroring Stripe's own
invoice); invoice history endpoint.

**Phase 9 — Proration Engine**: `ProrationCalculator` (pure function, heavily unit tested) +
`ledger_entries` table + `change-plan`/`preview-change` endpoints, wired through idempotency layer.

**Phase 10 — Dunning Workflow**: `dunning_attempts` table, `DunningPolicy` config,
`DunningScheduler` (`@Scheduled` poller), `DunningService`, `NotificationSender` +
`LoggingNotificationSender`, access-restriction check (a `@Component` used by a security filter or
`@PreAuthorize`-adjacent check on gated endpoints).

**Phase 11 — Admin Tooling**: admin list/detail endpoints, force-cancel, manual retry trigger,
audit log query endpoint.

**Phase 12 — Frontend**: Auth pages → plan listing → checkout redirect flow → dashboard (current
subscription + status banner) → billing history → upgrade/downgrade UI with live proration preview
→ admin views.

**Phase 13 — Hardening**: Testcontainers integration tests for the full webhook + idempotency +
proration flows, concurrency tests (parallel duplicate requests), README + architecture diagrams
finalized, GitHub Actions green end-to-end.

## 2. Subscription Creation

**Endpoint**: `POST /api/v1/subscriptions`, header `Idempotency-Key: <uuid>`, body `{ planId }`.

Flow:
1. Idempotency layer intercepts (see §11 below) — if this is a replay, short-circuits here.
2. Resolve `Customer` for the authenticated user; if none exists, create a Stripe Customer
   (`stripe.customers.create(email=...)`) and persist locally.
3. Look up `Plan` by `planId`; reject if inactive.
4. Reject if the customer already has a non-terminal subscription (MVP: one active subscription
   per customer — see PROJECT.md assumption 5).
5. Insert a local `Subscription` row with `status = INCOMPLETE`, `stripe_subscription_id = NULL`.
6. Call Stripe: create a Checkout Session, `mode = "subscription"`, `line_items = [{ price:
   plan.stripePriceId, quantity: 1 }]`, `customer = customer.stripeCustomerId`,
   `success_url`, `cancel_url`, and — importantly — pass our local `subscription.id` in
   `client_reference_id` (or `metadata`) so the webhook handler can correlate the eventual
   `checkout.session.completed` event back to this exact local row without ambiguity.
7. Return `{ subscriptionId, checkoutUrl: session.url }` to the frontend; the idempotency layer
   persists this response body against the key.
8. The local subscription remains `INCOMPLETE` until the webhook confirms payment (§6/§10 below).

**Pseudocode:**
```
function createSubscription(userId, planId, idempotencyKey):
    # idempotency check already performed by interceptor before this runs
    customer = customerRepo.findByUserId(userId)
    if customer is null:
        stripeCustomer = stripe.customers.create(email=user.email)
        customer = customerRepo.save(Customer(userId, stripeCustomer.id))

    plan = planRepo.findActiveById(planId) or throw NotFoundException

    existing = subscriptionRepo.findActiveByCustomer(customer.id)
    if existing exists:
        throw ConflictException("Customer already has an active subscription")

    subscription = subscriptionRepo.save(Subscription(
        customerId = customer.id, planId = plan.id,
        status = INCOMPLETE, stripeSubscriptionId = null
    ))

    session = stripe.checkout.sessions.create(
        mode = "subscription",
        customer = customer.stripeCustomerId,
        line_items = [{price: plan.stripePriceId, quantity: 1}],
        client_reference_id = subscription.id,
        success_url = ..., cancel_url = ...,
        idempotency_key = idempotencyKey  # also passed to Stripe's own API idempotency
    )

    auditLog.record(SUBSCRIPTION_CREATE_INITIATED, subscription.id, userId)
    return { subscriptionId: subscription.id, checkoutUrl: session.url }
```

## 3. Checkout & Payment Flow

Handled almost entirely by Stripe's hosted page (see ARCHITECTURE.md §4). The backend's
responsibility is limited to (a) creating the session correctly (§2 above) and (b) reacting to the
resulting webhook (§6). No payment form/card data logic exists on our backend or frontend beyond
redirecting to `session.url` and rendering the `success_url`/`cancel_url` pages.

## 4. Upgrade / Downgrade Flow

**Endpoints**:
- `POST /subscriptions/me/preview-change` `{ newPlanId }` → returns a `ProrationResult` with no
  side effects (pure calculation against current state).
- `POST /subscriptions/me/change-plan` `{ newPlanId }`, header `Idempotency-Key` → applies it.

Flow for `change-plan`:
1. Idempotency check (as always).
2. Load the customer's active `Subscription`; must be `ACTIVE` (reject `PAST_DUE`/other statuses
   with a clear error — you cannot upgrade while past due; must resolve payment first, matching
   real-world billing platform behavior).
3. Load current `Plan` and `newPlan`; reject if same plan.
4. Run `ProrationCalculator.calculate(currentPlan, newPlan, subscription.currentPeriodStart,
   subscription.currentPeriodEnd, now())` → `ProrationResult { creditCents, debitCents,
   netAmountCents, lineItems[] }` (algorithm in §5 below).
5. Call Stripe to update the underlying subscription's price
   (`stripe.subscriptions.update(subId, items=[{id: currentItemId, price: newPlan.stripePriceId}],
   proration_behavior="create_prorations")`) — **we let Stripe compute and charge/credit the actual
   proration on the real invoice**, while our local `ProrationCalculator` independently computes the
   same figures for (a) immediate UI preview before committing to the Stripe call, and (b) our own
   `ledger_entries`/`invoice_line_items` records, which must reconcile with what Stripe reports back
   via the subsequent `invoice.payment_succeeded`/`customer.subscription.updated` webhooks. This
   dual-computation is intentional: it's what makes the proration logic genuinely something *we*
   implemented (the resume claim) rather than just "we asked Stripe to do it," while still using
   Stripe as the actual payment rail — see the discussion in §5 "Why compute proration ourselves if
   Stripe can do it."
6. On Stripe success: update local `subscription.planId`, insert an adjustment `Invoice` (status
   reflecting whether it's net-credit/no-charge or net-debit/charged immediately depending on
   Stripe's `proration_behavior`), insert `invoice_line_items` (one row per `ProrationResult`
   line item), insert paired `ledger_entries` (a credit entry for unused time on the old plan, a
   debit entry for the new plan's remaining-period charge), all in **one transaction**.
7. On Stripe failure: local transaction never commits the plan change; return the Stripe error to
   the client.
8. Idempotency layer persists the response.

## 5. Proration Algorithm

**Policy** (PROJECT.md assumption 11): **daily proration**, matching Stripe's default behavior,
computed against the *current* billing period boundaries.

**Inputs**: `oldPlan.priceCents`, `newPlan.priceCents`, `periodStart`, `periodEnd` (the subscription's
current billing cycle boundaries), `changeAt` (now, or a specified effective instant).

**Definitions**:
- `totalDaysInPeriod = daysBetween(periodStart, periodEnd)` (using whole calendar days; a period is
  typically ~28–31 days for monthly, ~365/366 for annual).
- `remainingDays = daysBetween(changeAt, periodEnd)` (days left, inclusive of the change day or not —
  **convention: the change day itself belongs to the new plan**, so
  `remainingDays = daysBetween(changeAt.toLocalDate(), periodEnd.toLocalDate())`, computed on
  calendar dates in the **subscription's billing timezone (UTC, per assumption)** to avoid
  off-by-one errors from local client timezones).
- `unusedDays = remainingDays` on the **old** plan (the days not yet consumed on the plan being
  left).
- `oldPlanDailyRate = oldPlan.priceCents / totalDaysInPeriod` (using integer division with explicit
  rounding rule — see rounding note below).
- `newPlanDailyRate = newPlan.priceCents / totalDaysInPeriod`.
- `creditCents = round(oldPlanDailyRate * unusedDays)` — credit for unused time on the old plan.
- `debitCents = round(newPlanDailyRate * remainingDays)` — charge for the same remaining period on
  the new plan.
- `netAmountCents = debitCents - creditCents` (positive = customer owes more now; negative = credit
  applied to next invoice, matching Stripe's own semantics).

**Rounding rule**: All intermediate cent calculations use `BigDecimal` with `RoundingMode.HALF_EVEN`
(banker's rounding) at the *final* rounding step only — never round intermediate per-day rates and
then multiply (that compounds rounding error). Concretely:
`creditCents = (oldPlan.priceCents * unusedDays).divide(totalDaysInPeriod, 0, HALF_EVEN)` — multiply
first, divide once, round once.

**Pseudocode:**
```
function calculateProration(oldPlan, newPlan, periodStart, periodEnd, changeAt):
    totalDays = daysBetween(periodStart.toLocalDate(), periodEnd.toLocalDate())
    if totalDays <= 0: throw IllegalStateException("invalid billing period")

    remainingDays = daysBetween(changeAt.toLocalDate(), periodEnd.toLocalDate())
    remainingDays = clamp(remainingDays, 0, totalDays)   # defend against changeAt outside period

    creditCents = round( (oldPlan.priceCents * remainingDays) / totalDays )   # HALF_EVEN, single division
    debitCents  = round( (newPlan.priceCents * remainingDays) / totalDays )

    netCents = debitCents - creditCents

    lineItems = [
        LineItem("Unused time on " + oldPlan.name, -creditCents, type=PRORATION_CREDIT),
        LineItem("Remaining time on " + newPlan.name, +debitCents, type=PRORATION_DEBIT)
    ]

    return ProrationResult(creditCents, debitCents, netCents, lineItems, remainingDays, totalDays)
```

**Why compute proration ourselves if Stripe can do it**: Stripe's `proration_behavior=create_prorations`
does compute correct proration on Stripe's side and is what actually determines the real invoice
amount — we rely on it for financial correctness of the real charge. Our own `ProrationCalculator`
exists to (a) show the customer an accurate **preview before they commit** (Stripe does not offer a
pure client-facing "preview" without creating an upcoming-invoice preview call, which is a valid
alternative — see trade-off below), (b) populate our own explanatory `invoice_line_items` /
`ledger_entries` for a rich billing-history UI, and (c) be the artifact that demonstrates the actual
"implemented prorated billing logic" resume claim with testable, interview-explainable code, rather
than a one-line delegation to Stripe.

**Trade-off note (documented, pick one, recommended: option A)**:
- **Option A (recommended)**: Local `ProrationCalculator` for preview + record-keeping, Stripe
  `proration_behavior=create_prorations` for the actual charge, reconciled via webhook. Pros:
  demonstrates real algorithm design, instant preview with no Stripe round-trip. Cons: two
  computations must be kept consistent (mitigated by using identical day-count/rounding conventions
  and asserting equivalence in integration tests against Stripe's test-mode responses).
- **Option B**: Use Stripe's `invoices.retrieveUpcoming(...)` (preview endpoint) for the preview,
  skip local calculation entirely. Pros: guaranteed consistency with the real charge. Cons: requires
  a network call for every preview keystroke/interaction, and does not produce a standalone,
  interview-explainable proration algorithm — weaker demonstration of the resume claim. **Not
  recommended** as the primary path for this portfolio project, though it may be used as a
  cross-check in integration tests.

**Edge cases to test** (see TESTING.md for full matrix):
- Plan change on the first day of the period (`remainingDays == totalDays`, full credit/full debit).
- Plan change on the last day of the period (`remainingDays == 0` or `1` depending on inclusive
  convention — pick one, document it, test it).
- Leap-year February periods (`totalDays` = 29-day month).
- Annual plan periods (`totalDays` ≈ 365/366).
- Same-price plan "change" (lateral move) — net should be exactly 0, not a rounding-induced ±1 cent.
- Two changes within the same billing period (upgrade then downgrade before period end) — the
  *second* proration must be calculated against the period boundaries and the *current* plan at the
  time of the second change (which is the first new plan), not the original plan — i.e., proration
  is always relative to "whatever plan is active right now," recursively correct by construction
  since each call reads `subscription.planId` fresh.
- Downgrade producing a **net credit** (debit < credit): must not attempt to charge a negative
  amount; the negative `netAmountCents` becomes a credit applied to the *next* invoice
  (`invoice_line_items` type `PRORATION_CREDIT` with no immediate charge — mirrors Stripe's own
  "credit balance" concept).

## 6. Invoice Generation

Two invoice-creation triggers, both converging on the same `InvoiceService.createOrSync(...)` method:

1. **Recurring cycle invoices**: driven by the `invoice.payment_succeeded` / `invoice.payment_failed`
   webhooks. On receipt, look up (or create, if this is the first time we've seen this
   `stripe_invoice_id`) the local `Invoice` row, sync `status`, `amount_due_cents`,
   `amount_paid_cents`, `period_start/end`, `paid_at`. This keeps our invoice table a faithful mirror
   of Stripe's invoice for that subscription.
2. **Proration adjustment invoices**: created synchronously during `change-plan` (§4 step 6) using
   the locally computed `ProrationResult`, then **reconciled** against the real Stripe invoice that
   Stripe generates for the proration (Stripe creates an invoice item automatically when
   `create_prorations` triggers immediate invoicing) via the same webhook sync path — if a
   `stripe_invoice_id` mismatch or amount mismatch is detected between our locally-created row and
   what Stripe reports, log a `WARN` with both values for investigation (this is the kind of
   reconciliation discipline that separates a real billing system from a toy one).

**Idempotent invoice sync pseudocode:**
```
function syncInvoiceFromStripe(stripeInvoice):
    invoice = invoiceRepo.findByStripeInvoiceId(stripeInvoice.id)
    if invoice is null:
        invoice = new Invoice(stripeInvoiceId = stripeInvoice.id, subscriptionId = resolve(...))
    invoice.status = mapStatus(stripeInvoice.status)
    invoice.amountDueCents = stripeInvoice.amount_due
    invoice.amountPaidCents = stripeInvoice.amount_paid
    invoice.periodStart = stripeInvoice.period_start
    invoice.periodEnd = stripeInvoice.period_end
    invoice.paidAt = stripeInvoice.status == "paid" ? now() : invoice.paidAt
    invoiceRepo.save(invoice)   # upsert by unique stripe_invoice_id — safe to call repeatedly
    return invoice
```
Note this function is naturally idempotent: calling it twice with the same Stripe invoice payload
produces the same end state (upsert-by-unique-key), which is exactly what's needed since it's called
from webhook handlers that are themselves subject to redelivery.

## 7. Billing Cycle Management

- Stripe owns the actual recurring billing clock (it generates invoices and attempts charges on
  schedule per the subscription's `Price` interval). Our backend does **not** independently decide
  "time to bill this customer" — that would risk double-billing against Stripe's own schedule.
- Our responsibility is to **stay in sync**: `current_period_start`/`current_period_end` on the
  local `subscriptions` row are updated from `customer.subscription.updated` webhook payloads
  (`current_period_start`, `current_period_end` fields), and drive our own UI ("renews on...") and
  the proration calculator's period boundaries.
- A `SubscriptionRenewalReconciliationJob` (scheduled, e.g. daily) queries Stripe for any `ACTIVE`
  local subscription whose `current_period_end` has passed without a corresponding webhook update
  being received within a grace window (e.g., 6 hours) — a safety net against missed/lost webhooks,
  logging a `WARN`/alert and self-healing by re-fetching the subscription from Stripe directly. This
  demonstrates defensive engineering around "webhooks are not 100% guaranteed in a timely manner."

## 8. Dunning Workflow

**Trigger**: `invoice.payment_failed` webhook received for a recurring invoice.

**On first failure for an invoice**:
1. `InvoiceService.syncInvoiceFromStripe(...)` marks the invoice `OPEN` (unpaid), records a
   `PaymentAttempt` row (`status=FAILED`, `failure_code`, `failure_message` from the Stripe event).
2. `SubscriptionService` transitions the subscription `ACTIVE -> PAST_DUE` (via the state machine).
3. `DunningService.scheduleRetries(invoice)` reads the configured `DunningPolicy` (default offsets
   `[1d, 3d, 5d, 7d]`) and inserts `dunning_attempts` rows with `status=PENDING`,
   `scheduled_at = failureTime + offset`, one per configured offset, `attempt_number` 1..N.
4. `NotificationSender.send(customer, PAYMENT_FAILED_TEMPLATE, {attemptsRemaining, nextRetryAt})`.
5. Audit log entry `DUNNING_STARTED`.

**Scheduler** (`DunningScheduler`, `@Scheduled(fixedDelay = ...)`, e.g. every 15 minutes for demo
purposes, documented as "would be every few minutes to hourly in production depending on retry
granularity needed"):
```
function pollAndExecuteDueDunningAttempts():
    dueAttempts = dunningAttemptRepo.findByStatusAndScheduledAtBefore(PENDING, now())
    for attempt in dueAttempts:
        executeAttempt(attempt)   # each in its own transaction, one failure doesn't block others

function executeAttempt(attempt):
    invoice = invoiceRepo.findById(attempt.invoiceId)
    if invoice.status == PAID:
        # payment already recovered through another channel (e.g. customer updated card
        # and Stripe's own Smart Retry succeeded before our scheduled attempt fired)
        attempt.status = SKIPPED
        dunningAttemptRepo.save(attempt)
        return

    result = stripe.invoices.pay(invoice.stripeInvoiceId)   # attempt to charge again
    paymentAttemptRepo.save(PaymentAttempt(invoiceId, result.status, result.failureCode))

    if result.status == "succeeded":
        attempt.status = SUCCEEDED
        subscriptionService.transitionTo(subscription, ACTIVE)   # restores access
        notificationSender.send(customer, PAYMENT_RECOVERED_TEMPLATE)
        auditLog.record(DUNNING_RECOVERED, subscription.id)
        # cancel any remaining PENDING attempts for this invoice
        dunningAttemptRepo.cancelRemaining(invoice.id)
    else:
        attempt.status = FAILED
        isLastAttempt = attempt.attemptNumber == dunningPolicy.maxAttempts
        if isLastAttempt:
            subscriptionService.transitionTo(subscription, UNPAID)
            accessRestrictionService.restrict(subscription)
            notificationSender.send(customer, FINAL_NOTICE_TEMPLATE)
            auditLog.record(DUNNING_EXHAUSTED, subscription.id)
        else:
            notificationSender.send(customer, RETRY_FAILED_TEMPLATE, {nextRetryAt: nextAttempt.scheduledAt})
    dunningAttemptRepo.save(attempt)
```

**Recovery path**: if Stripe's own retry (or the customer manually updating their payment method and
retrying via the Customer Portal / a "retry now" button) succeeds *before* our next scheduled
attempt, the `invoice.payment_succeeded` webhook fires independently and:
```
function onInvoicePaymentSucceeded(stripeInvoice):
    invoice = invoiceService.syncInvoiceFromStripe(stripeInvoice)   # marks PAID
    subscription = subscriptionRepo.findBySubscriptionInvoice(invoice)
    if subscription.status in [PAST_DUE, UNPAID]:
        subscriptionService.transitionTo(subscription, ACTIVE)
        dunningAttemptRepo.cancelRemaining(invoice.id)   # stop the scheduled retry chain
        accessRestrictionService.restore(subscription)
        auditLog.record(DUNNING_RECOVERED_VIA_WEBHOOK, subscription.id)
```
This dual recovery path (scheduler-driven retry success, and independent webhook-driven recovery) is
exactly the kind of race the system must handle correctly — both paths call the same
`transitionTo(ACTIVE)` + `cancelRemaining(...)` logic, so whichever fires first "wins" cleanly and
the other becomes a no-op (transitioning `ACTIVE -> ACTIVE` and canceling an already-empty remaining
set are both safe/idempotent operations).

## 9. Stripe Synchronization

General principle stated in ARCHITECTURE.md §1: **all Stripe-driven state changes flow through the
same service methods as user-driven changes.** Concretely, `customer.subscription.updated` handler:
```
function onCustomerSubscriptionUpdated(stripeSub):
    subscription = subscriptionRepo.findByStripeSubscriptionId(stripeSub.id)
    if subscription is null:
        log.warn("Received update for unknown subscription {}", stripeSub.id)
        return   # not an error — could be a subscription created outside our system in test mode

    incomingEventTime = stripeSub.eventCreatedAt  # from the Stripe Event envelope, not the object
    if incomingEventTime <= subscription.lastSyncedFromStripeAt:
        log.info("Ignoring stale webhook for subscription {}", subscription.id)
        return   # out-of-order delivery guard, see ARCHITECTURE.md §6

    subscription.currentPeriodStart = stripeSub.current_period_start
    subscription.currentPeriodEnd = stripeSub.current_period_end
    subscription.status = mapStripeStatus(stripeSub.status)   # via SubscriptionStateMachine.applyExternal(...)
    subscription.lastSyncedFromStripeAt = incomingEventTime
    subscriptionRepo.save(subscription)
```

## 10. Webhook Deduplication

Already detailed in ARCHITECTURE.md §6; implementation detail — the persist step uses an
`INSERT ... ON CONFLICT (stripe_event_id) DO NOTHING` (or an equivalent unique-constraint catch)
so that even genuinely concurrent delivery of the same event ID (rare but possible if Stripe races a
redelivery against a slow initial response) can only result in one row:
```
function receiveWebhook(rawPayload, signatureHeader):
    event = stripe.webhooks.constructEvent(rawPayload, signatureHeader, webhookSigningSecret)
        # throws SignatureVerificationException -> caller returns 400

    inserted = webhookEventRepo.insertIfNotExists(event.id, event.type, rawPayload)  # ON CONFLICT DO NOTHING
    if not inserted:
        existing = webhookEventRepo.findByStripeEventId(event.id)
        if existing.status == PROCESSED:
            return 200 OK   # true duplicate, already handled
        # else: status RECEIVED or FAILED from a prior attempt -> fall through and reprocess

    try:
        handler = dispatcher.resolve(event.type)
        if handler is null:
            webhookEventRepo.markProcessed(event.id)   # unhandled type is not an error, just a no-op
            return 200 OK
        handler.handle(event)
        webhookEventRepo.markProcessed(event.id)
        return 200 OK
    catch (Exception e):
        webhookEventRepo.markFailed(event.id, e.message)
        return 500   # Stripe will retry with backoff
```

## 11. Idempotency Implementation

Enforced via a Spring `HandlerInterceptor` (or a servlet `Filter`, or an AOP `@Aspect` around
`@IdempotentEndpoint`-annotated controller methods — **recommendation: a custom annotation +
`@Aspect`**, because it makes which endpoints require idempotency explicit and inspectable in code,
versus a blanket filter that requires path-matching configuration to know which routes apply).

```
@IdempotentEndpoint
POST /subscriptions
POST /subscriptions/me/change-plan
POST /subscriptions/me/cancel
```

**Aspect pseudocode:**
```
around(@IdempotentEndpoint method call):
    key = request.header("Idempotency-Key")
    if key is missing: throw BadRequestException("Idempotency-Key header is required")

    requestHash = sha256(canonicalize(request.body))
    userId = currentUser.id

    # Atomic claim attempt
    claimed = idempotencyKeyRepo.insertIfNotExists(
        key, userId, endpoint = request.path, requestHash, status = IN_PROGRESS
    )   # unique constraint on `idem_key`; ON CONFLICT DO NOTHING, returns whether row was inserted

    if not claimed:
        existing = idempotencyKeyRepo.findByKey(key)   # re-fetch inside same or new read
        if existing.requestHash != requestHash:
            throw ConflictException("Idempotency key reused with a different request body")
        if existing.status == IN_PROGRESS:
            throw ConflictException("A request with this idempotency key is already being processed")
        if existing.status == COMPLETED:
            return HttpResponse(existing.responseStatus, existing.responseBody)   # exact replay

    try:
        result = proceed()   # actual controller/service method executes
        idempotencyKeyRepo.markCompleted(key, result.status, serialize(result.body))
        return result
    catch (Exception e):
        idempotencyKeyRepo.delete(key)   # or mark FAILED and allow retry — see note below
        throw e
```

**Note on failure handling**: two valid strategies exist —
(a) delete/release the key on failure so the client can safely retry with the *same* key (treats
failed attempts as "didn't happen"), or
(b) keep a `FAILED` status permanently against that key (treats the key as burned, forcing the client
to generate a new key for a genuinely new attempt).
**Recommendation: (a)** for this project — a failed attempt (e.g., transient Stripe error) should be
safely retryable by the client reusing the same key, which matches Stripe's own idempotency key
semantics (Stripe does not "burn" an idempotency key on a failed request).

**Concurrency test target**: two simultaneous requests with the same key and same body — exactly one
should succeed and perform the mutation; the other should either receive the replayed result (if it
arrives after the first completes) or a 409 "in progress" (if truly concurrent) — never both
executing the business logic. This is asserted via a concurrency integration test (TESTING.md §7).

## 12. Background Jobs

| Job | Trigger | Purpose |
|---|---|---|
| `DunningScheduler.pollAndExecuteDueDunningAttempts` | `@Scheduled(fixedDelay)` | Execute due payment retries |
| `IdempotencyKeyCleanupJob` | `@Scheduled(cron daily)` | Delete `idempotency_keys` rows past `expires_at` |
| `WebhookEventCleanupJob` (optional/advanced) | `@Scheduled(cron weekly)` | Archive/delete old processed webhook events beyond a retention window |
| `SubscriptionRenewalReconciliationJob` | `@Scheduled(cron daily)` | Self-heal subscriptions whose period ended without a webhook update (§7) |
| `CheckoutSessionExpirySweepJob` | `@Scheduled(fixedDelay)` | Transition `INCOMPLETE` subscriptions older than 24h to `EXPIRED` |

All jobs are implemented as `@Component` classes with a single `@Scheduled` method delegating to a
service method — the service method itself is unit-testable without the scheduler (the scheduler is
just a trigger).

## 13. Access Control (Restriction Enforcement)

`AccessRestrictionService.isAccessAllowed(subscription)`:
```
function isAccessAllowed(subscription):
    if subscription.status == ACTIVE: return true
    if subscription.status == PAST_DUE:
        # grace period: allow access for a configurable window after first failure
        # (e.g., 3 days) so a customer isn't locked out on the very first missed payment
        return now() < subscription.pastDueSince + gracePeriodDuration
    return false   # UNPAID, CANCELED, EXPIRED, INCOMPLETE
```
Enforced via a method-level check (`@PreAuthorize`-style custom expression, or an explicit
`if (!accessRestrictionService.isAccessAllowed(sub)) throw ForbiddenException(...)` at the top of
gated service methods) on whatever "premium feature" endpoints exist in the demo app (for a billing
platform portfolio project, this can be a simple `GET /api/v1/premium/content` sample endpoint whose
entire purpose is to demonstrate the access-gating mechanism working end-to-end with the dunning
state machine).

## 14. Error Handling

- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps: `NotFoundException` → 404,
  `ConflictException` → 409, `ForbiddenException` → 403, `BadRequestException`/
  `ConstraintViolationException` → 400, `StripeException` (from the SDK) → mapped to a 502 "upstream
  payment provider error" with a sanitized message (raw Stripe error details logged server-side,
  not necessarily all exposed to the client), anything unhandled → 500 with a generic body (no stack
  traces to clients).
- Every exception path that represents a business-relevant failure (payment failure, Stripe API
  error during a mutating call) is logged with enough context (subscription id, customer id,
  Stripe error code) to debug without needing to reproduce.
- Stripe SDK calls are wrapped so that `StripeException` subtypes (`CardException`,
  `RateLimitException`, `InvalidRequestException`, `APIConnectionException`) are handled distinctly
  where behavior differs (e.g., `RateLimitException`/`APIConnectionException` are retryable per
  ARCHITECTURE.md §12, `CardException` is not retryable and should surface as a clear "payment
  declined" response).

## 15. Concurrency Handling

- **Optimistic locking** (`@Version` on `subscriptions`) guards against lost updates when the API
  path and webhook path race to update the same subscription row — a `StaleObjectStateException` on
  save triggers a retry of the read-modify-write with fresh data (bounded retry count, e.g., 3).
- **Idempotency key uniqueness** (DB unique constraint, not just application-level checking) guards
  against duplicate mutating requests, as detailed in §11.
- **Webhook event uniqueness** (DB unique constraint on `stripe_event_id`) guards against duplicate
  webhook processing, as detailed in §10.
- **Dunning scheduler safety**: `dunning_attempts` are claimed via a `SELECT ... FOR UPDATE SKIP
  LOCKED` style query (or an application-level `status` transition guarded by an atomic
  `UPDATE ... WHERE status='PENDING'` with the row-count checked) if the scheduler were ever run on
  multiple instances — documented per ARCHITECTURE.md §14 as a single-instance assumption for MVP,
  with this as the documented upgrade path.

## 16. Edge Cases (Cross-Cutting Checklist)

- Customer attempts to subscribe while already `INCOMPLETE` from a prior abandoned checkout →
  reuse/expire-and-recreate the existing `INCOMPLETE` row rather than creating a second one (unique
  partial index or application check: at most one non-terminal subscription per customer).
- Webhook arrives for a `stripe_subscription_id` we don't recognize (e.g., test data created
  directly in the Stripe Dashboard) → log and no-op, don't throw (§9 pseudocode).
- Customer downgrades to a plan cheaper than the accumulated credit from a *previous* downgrade in
  the same period → credits accumulate in `ledger_entries` and net against the *next* real invoice,
  never producing a negative charge attempt.
- Stripe webhook signature verification fails → 400, event never persisted, no retry expected from
  Stripe (400 is treated as "don't retry" by Stripe for malformed requests — confirm against current
  Stripe docs during implementation since retry semantics per status code can be nuanced).
- Dunning retry succeeds but the customer had already manually canceled in the meantime → the
  `invoice.payment_succeeded` handler must check current subscription status before blindly setting
  `ACTIVE`; a `CANCELED` subscription should not be resurrected by a late-arriving payment success
  (business decision, documented: payment succeeding after cancellation results in a credit balance
  or refund consideration, not reactivation — this is a good "I thought about this" talking point
  for an interview).
- Two upgrade requests submitted with two *different* idempotency keys in rapid succession
  (not a duplicate — a genuinely fast double-change) → both are legitimate distinct operations; the
  second one's proration calculation must read the subscription state *as updated by the first*
  (enforced naturally by both running through the same `@Transactional` service method sequentially,
  with optimistic locking catching any true race).
