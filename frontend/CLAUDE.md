# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run dev      # Dev server on :5173 — proxies /api/* to http://localhost:8080
npm run build    # tsc + vite build (fails on type errors)
npm run lint     # ESLint with --max-warnings 0 (zero-tolerance)
npm run preview  # Serve the production build locally
```

## Source structure

```
src/
├── lib/api.ts        All API calls + shared TypeScript types (Account, Goal, etc.)
├── lib/utils.ts      Utility helpers
├── hooks/            Custom hooks wrapping React Query (useAccounts, useGoals, useDashboard, useAuth)
├── router.tsx        Route definitions + RequireAuth guard
├── components/
│   ├── layout/       Layout + Sidebar (persistent shell around all authenticated pages)
│   └── shared/       Reusable primitives: GlassCard, GlowBackground, PageHeader
└── pages/            One directory per route: login, dashboard, accounts, goals, sync, settings
```

## Key patterns

**API layer (`lib/api.ts`):** Single Axios instance with `withCredentials: true`. A response interceptor silently calls `POST /auth/refresh` on any 401, queues concurrent retries, then re-fires the original requests. Auth calls (`/auth/*`) are excluded to avoid infinite loops. All typed API functions (`accountsApi`, `goalsApi`, etc.) live here — add new endpoints here, not inline in components.

**Server state:** React Query manages all remote data. Hooks in `hooks/` own query keys and mutation logic; pages just call the hooks. Keep query keys co-located in the hook file.

**Auth guard:** `RequireAuth` in `router.tsx` checks `sessionStorage.getItem('picsou_user')`. The auth cookie is HttpOnly (invisible to JS) — `picsou_user` is the JS-readable signal that a session exists. It is set on login and cleared on logout.

**Routing:** `/sync/callback` and `/sync` share `SyncPage` — the callback path is the OAuth redirect target from Enable Banking.

## Conventions

- TypeScript types for the API surface are all in `lib/api.ts` — do not redefine them in component files.
- ESLint is zero-warnings; fix lint errors before considering a change done.
- `tsc` runs as part of `npm run build` — the build will fail on type errors.
- Tailwind for all styling; no CSS modules or styled-components.
