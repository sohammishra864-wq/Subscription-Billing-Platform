# Subscription Billing Platform

A production-grade SaaS billing engine handling the full subscription lifecycle: plan selection, checkout via Stripe, recurring billing, prorated upgrades/downgrades, invoice generation, failed-payment recovery (dunning), and access control based on subscription health.

## Key Engineering Features

- **Prorated billing** — daily-rate proration engine with credit/debit ledger entries
- **Idempotent APIs** — `Idempotency-Key` header with persisted key/hash/response, safe under concurrency
- **Stripe webhook deduplication** — signature verification, event log, idempotent handlers
- **Dunning workflow** — scheduled retry engine, status transitions, access restriction, recovery paths

## Tech Stack

- **Backend**: Java 21, Spring Boot 3, PostgreSQL, Flyway, Stripe Java SDK
- **Frontend**: React 18, TypeScript, Tailwind CSS, Vite
- **Infra**: Docker Compose, GitHub Actions CI

## Quick Start

```bash
cp .env.example .env
# Fill in your Stripe test-mode keys in .env
docker compose -f infra/docker-compose.yml up
```

Backend: http://localhost:8080
Frontend: http://localhost:5173

## Project Structure

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full system design and [docs/EPICS.md](docs/EPICS.md) for implementation roadmap.
