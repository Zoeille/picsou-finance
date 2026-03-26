# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Each submodule has its own CLAUDE.md with detailed guidance: [`backend/CLAUDE.md`](backend/CLAUDE.md) and [`frontend/CLAUDE.md`](frontend/CLAUDE.md).

## Full-stack startup

```bash
docker compose up --build   # Build and start all services (frontend :5173, backend :8080, PostgreSQL :5432)
docker compose down
```

For local development without Docker, start the backend first (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`), then the frontend (`npm run dev`) — Vite proxies `/api/*` to `:8080`.

## Architecture

Picsou is a self-hosted personal finance dashboard. **Single-user** — no registration, credentials come from environment variables (`APP_USERNAME`, `APP_PASSWORD_HASH`).

```
React (Vite :5173) ←→ Spring Boot (:8080) ←→ PostgreSQL (:5432)
                               ↓
               Enable Banking API (bank sync via PSD2/OAuth)
               CoinGecko API (crypto prices)
               Yahoo Finance API (stock/ETF prices)
```

**Auth contract between frontend and backend:** JWT tokens travel as HttpOnly + SameSite=Strict cookies (never in JS-readable storage). The frontend Axios instance auto-refreshes on 401; the backend rotates the refresh token on every use.

**Contract between backend and external providers:** all integrations hide behind `BankConnectorPort` and `PriceProviderPort` — swapping a provider means adding an adapter, not touching services or controllers.

## Configuration

All secrets are environment variables. Copy `.env.example` to `.env` before first run. Required: `JWT_SECRET`, `APP_USERNAME`, `APP_PASSWORD_HASH` (bcrypt). Enable Banking variables are optional if bank sync is not needed.
