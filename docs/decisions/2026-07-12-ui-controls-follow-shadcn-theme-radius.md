# ADR: UI controls follow the shadcn theme radius, not a pill shape

> Date: 2026-07-12
> Status: ❌ Superseded by [2026-08-10 — One radius ladder for every surface](./2026-08-10-ui-radius-ladder.md)
>
> **What changed:** the principle below still stands — shape derives from `--radius`, `rounded-full`
> is reserved for naturally circular elements, and app code never re-shapes a control. Only the
> specific rungs changed: controls are now `rounded-lg` (not `rounded-md`) and their containers
> `rounded-xl` (not `rounded-lg`), and the ladder now assigns a tier to panels, rows, and micro
> elements too. Read the superseding ADR for the current table.

## Context

Picsou's UI is built on shadcn/ui primitives over a Tailwind v4 theme whose corner radius derives
from a single token, `--radius: 0.625rem` (10px), defined in `frontend/src/index.css`. Cards,
inputs, sidebar items, and menus all read their radius from that scale, so the app looks like one
coherent system.

The large frontend rewrite in commit `716228e` (PR #29, agent-generated) broke that coherence: it
changed the shape of the interactive controls without touching the theme token. Specifically it set
`Button` to `rounded-full`, `Tabs` and `DropdownMenu` to `rounded-2xl`/`rounded-xl`, and hardcoded
`rounded-full` filter chips across pages. The result was fully-round "pill" controls sitting next to
`rounded-lg`/`rounded-xl` cards — the controls looked like they came from a different design system.
The regression was invisible in review because the same PR also rewrote the frontend convention to
declare pills the standard (addressed separately in [`docs/CODING_RULES.md`](../CODING_RULES.md)
rule 0). A human caught it visually weeks later.

This ADR settles the shape question so it is not re-litigated per component or per PR.

## Decision

**Interactive text controls follow the shadcn theme radius derived from `--radius`:**

- Controls (buttons, filter chips, tabs/segmented items, dropdown menu items) → `rounded-md`
- Their containers (segmented control, dropdown/popover) → `rounded-lg`
- Cards and large surfaces stay rounder (`rounded-xl` … `rounded-4xl`) — deliberately more than controls
- `rounded-full` is reserved for elements that are circular by nature: avatars, switch thumbs,
  badges, status dots, scrollbars

Shape is **owned by the shadcn `ui/` primitives**. App code composes `<Button>`/`<Tabs>` and inherits
the correct radius; it must never override a control's radius with a local `rounded-*` className.

## Alternatives considered

### A. Full-pill control set (`rounded-full` everywhere)

- **Pros**:
  - Modern, friendly "consumer fintech" aesthetic (Stripe, Wise, Revolut lean this way)
  - Was already partially in place after #29, so zero migration
- **Cons**:
  - Only coherent if the *whole* control set commits — inputs, badges, and containers would also
    have to become pills, a much larger change
  - Pills read as playful; a data-dense net-worth/budget dashboard wants controls that feel precise,
    not toy-like
  - Fully-round controls next to rectangular cards is the exact mismatch that triggered this ADR

### B. Per-component radius chosen case by case

- **Pros**:
  - Maximum flexibility per surface
- **Cons**:
  - No single source of truth; guaranteed drift over time
  - Every new component becomes a shape debate
  - Defeats the purpose of the `--radius` token

### C. Controls follow the theme scale (`rounded-md` / `rounded-lg`) — **chosen**

- **Pros**:
  - One token (`--radius`) re-shapes the whole app coherently
  - Matches the shadcn defaults, so `shadcn add`/upgrades stay low-friction
  - Controls, inputs, and cards form one visual family
- **Cons**:
  - Less visually distinctive than a bold pill language
  - Requires vigilance against local `rounded-full` overrides (mitigated by convention + review)

## Reasoning

Coherence beats novelty for a finance tool: the product's job is to make dense numeric data legible,
and a consistent, calm control language serves that better than a distinctive-but-clashing pill
shape. Anchoring shape to the existing `--radius` token means the decision is enforced by a single
knob rather than per-component discipline, and it keeps us aligned with shadcn defaults so future
`ui/` regenerations don't fight us. Choosing pills would have obligated a full pill migration
(inputs, badges, containers) to avoid the very mismatch we were fixing — more work for a look that
suits the product less.

## Trade-offs accepted

- **Less visual signature**: the UI is intentionally understated rather than boldly pill-branded.
  Acceptable — brand expression lives in color, logo, and typography, not control roundness.
- **Override vigilance**: a stray `className="rounded-full"` on a `<Button>` silently wins over the
  primitive (tailwind-merge keeps the last radius class). We accept relying on the convention Don'ts
  and review to catch these, rather than a lint rule (none exists yet).

## Consequences

- `button.tsx` base is `rounded-md`; `tabs.tsx` is `rounded-lg` list / `rounded-md` trigger;
  `dropdown-menu.tsx` is `rounded-lg` container / `rounded-md` items. Do not edit these off the scale.
- App code must not add `rounded-full`/`rounded-xl`/`rounded-2xl` to interactive text controls, and
  must not override a shadcn primitive's radius via className.
- New controls start from the shadcn primitive and inherit shape — no per-page radius styling.
- Enforced by the Don'ts in [`docs/conventions/frontend.md`](../conventions/frontend.md) and the
  charter in [`docs/CODING_RULES.md`](../CODING_RULES.md).

## Related features

- [UI control shape (shadcn theme radius)](../features/ui-control-shape-system.md) — implementation detail and gotchas
- [Theme (dark / light / system)](../features/theme-persistence.md) — the theme tokens these radii derive from
