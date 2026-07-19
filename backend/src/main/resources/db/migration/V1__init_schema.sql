-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'CUSTOMER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

-- Refresh tokens
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- Plans
CREATE TABLE plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    stripe_price_id VARCHAR(255) NOT NULL,
    price_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    billing_interval VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_plans_stripe_price_id UNIQUE (stripe_price_id),
    CONSTRAINT chk_plans_interval CHECK (billing_interval IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT chk_plans_price_positive CHECK (price_cents > 0)
);

-- Customers (link user to Stripe)
CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_customer_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_customers_user_id UNIQUE (user_id),
    CONSTRAINT uk_customers_stripe_customer_id UNIQUE (stripe_customer_id)
);

-- Subscriptions
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    plan_id UUID NOT NULL REFERENCES plans(id),
    stripe_subscription_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'INCOMPLETE',
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    canceled_at TIMESTAMPTZ,
    last_synced_from_stripe_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_subscriptions_stripe_sub_id UNIQUE (stripe_subscription_id),
    CONSTRAINT chk_subscriptions_status CHECK (status IN ('INCOMPLETE', 'ACTIVE', 'PAST_DUE', 'UNPAID', 'CANCELED', 'EXPIRED'))
);

-- At most one non-terminal subscription per customer
CREATE UNIQUE INDEX idx_subscriptions_one_active_per_customer
    ON subscriptions(customer_id)
    WHERE status NOT IN ('CANCELED', 'EXPIRED');

CREATE INDEX idx_subscriptions_customer_id ON subscriptions(customer_id);

-- Invoices
CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    stripe_invoice_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    amount_due_cents BIGINT NOT NULL DEFAULT 0,
    amount_paid_cents BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    issued_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    CONSTRAINT uk_invoices_stripe_invoice_id UNIQUE (stripe_invoice_id),
    CONSTRAINT chk_invoices_status CHECK (status IN ('DRAFT', 'OPEN', 'PAID', 'UNCOLLECTIBLE', 'VOID'))
);

CREATE INDEX idx_invoices_subscription_id ON invoices(subscription_id);

-- Invoice line items
CREATE TABLE invoice_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description VARCHAR(500) NOT NULL,
    amount_cents BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_line_item_type CHECK (type IN ('SUBSCRIPTION', 'PRORATION_CREDIT', 'PRORATION_DEBIT'))
);

CREATE INDEX idx_invoice_line_items_invoice_id ON invoice_line_items(invoice_id);

-- Ledger entries (append-only)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    related_invoice_id UUID REFERENCES invoices(id),
    entry_type VARCHAR(50) NOT NULL,
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('CREDIT', 'DEBIT'))
);

CREATE INDEX idx_ledger_entries_subscription_id ON ledger_entries(subscription_id);

-- Payment attempts
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    stripe_payment_intent_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_attempts_invoice_id ON payment_attempts(invoice_id);

-- Dunning attempts
CREATE TABLE dunning_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    attempt_number INT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    scheduled_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    CONSTRAINT chk_dunning_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELED'))
);

CREATE INDEX idx_dunning_attempts_poll ON dunning_attempts(status, scheduled_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_dunning_attempts_subscription_id ON dunning_attempts(subscription_id);

-- Idempotency keys
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    endpoint VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_idempotency_keys_idem_key UNIQUE (idem_key),
    CONSTRAINT chk_idem_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys(expires_at);

-- Webhook events
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'RECEIVED',
    payload JSONB NOT NULL,
    error_message TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT uk_webhook_events_stripe_event_id UNIQUE (stripe_event_id),
    CONSTRAINT chk_webhook_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

-- Audit logs
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    actor_user_id UUID REFERENCES users(id),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id, created_at DESC);
