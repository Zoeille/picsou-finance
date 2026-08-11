# Feature: UI radius ladder (shadcn theme radius)

> Last updated: 2026-08-10
>
> **Status:** the ladder below is ratified by
> [ADR 2026-08-10](../decisions/2026-08-10-ui-radius-ladder.md) and applied to the codebase by a
> separate sweep (split out per [`CODING_RULES.md`](../CODING_RULES.md) rule 0). Until that sweep
> lands, the primitives named under *Key files* still carry their pre-ladder radii — `button.tsx` is
> `rounded-md`, `dialog.tsx` is `rounded-xl` — so read this note as the target, not as a description
> of `main`.

## Context

Every box in the app — from a 26px card down to a 6px checkbox — must read as one design system.
Corner radius is not chosen per component: it derives from a single theme token, `--radius`, and
each kind of element is assigned a fixed rung of the derived scale. This note exists because the
consistency has broken twice: once when a rewrite turned every control into a pill (2026-07-12), and
once because only *controls* had an assigned rung, so everything between a control and a card drifted
(2026-08-10).

## How it works

`--radius: 0.625rem` (10px) is defined once in `frontend/src/index.css`. Tailwind v4 derives the
whole scale from it as multiples, which lands on an evenly-spaced ladder:

| Token | `rounded-sm` | `rounded-md` | `rounded-lg` | `rounded-xl` | `rounded-2xl` | `rounded-3xl` | `rounded-4xl` |
|-------|----|----|----|----|----|----|----|
| px    | 6  | 8  | 10 | 14 | 18 | 22 | 26 |

Elements pick a rung by **what they are**; they never hardcode a pixel radius or a pill shape. The
one exception is the chart-mark row below: under ~10px every rung reads as a circle, so
`rounded-[2px]` is sanctioned there and nowhere else.

| Tier | Class | ≈ px | Applies to |
|------|-------|------|-----------|
| **Surface** | `rounded-4xl` | 26 | `Card`, `DialogContent`, bottom sheet, desktop sidebar shell |
| **Panel** | `rounded-2xl` | 18 | boxes inside a surface: callouts, `<pre>` code blocks, dropzones, empty states, bordered sub-panels, stat blocks, mobile nav bar |
| **Row / control container** | `rounded-xl` | 14 | list containers and rows, nav rows, inline alert strips, segmented-control wrappers, dropdown/popover content, chart tooltips |
| **Control** | `rounded-lg` | 10 | button, input, select, textarea, segment item, menu item, OTP slot, icon tile (`size-8`…`size-12`), single-line code-copy value |
| **Micro** | `rounded-md` | 8 | skeleton default, `size-6` tile, treemap cell, small overlay chip |
| **Hairline** | `rounded-sm` | 6 | checkbox, `size-4` logo |
| **Circular** | `rounded-full` | — | *identity* avatar (see the design system), switch, badge, status dot, progress bar, step bubble, color swatch, decorative empty-state icon bubble |
| **Chart mark** | `rounded-[2px]` | 2 | chart swatches, legend squares, thin bar segments, tooltip arrow (≤ 10px marks) |

The ladder is **concentric**: at every nesting step, the parent's radius equals the child's radius
plus the padding between them. A `TabsList` is `p-1` (4px) at `rounded-xl` (14) holding a
`rounded-lg` (10) trigger; a card is `px-4` (16px) at `rounded-4xl` (26) holding a `rounded-lg` (10)
control. Picking a rung off the ladder breaks that alignment visibly.

Shape lives in the shadcn primitives, so a page composes `<Button>` / `<Tabs>` / `<Card>` / `<Item>`
and inherits the correct radius. App code must not restate or override a radius the primitive
already provides.

### Key files

- `frontend/src/index.css` — `--radius` base token and the derived scale (`@theme inline`)
- `frontend/src/components/ui/card.tsx` — `rounded-4xl` surface (unchanged since 2026-07-12)
- `frontend/src/components/ui/dialog.tsx` — `rounded-4xl`; a modal is a surface, it matches the cards behind it
- `frontend/src/components/ui/button.tsx` — `rounded-lg` control base
- `frontend/src/components/ui/input.tsx`, `input-otp.tsx` — `rounded-lg` control
- `frontend/src/components/ui/tabs.tsx` — list `rounded-xl`, trigger `rounded-lg`
- `frontend/src/components/ui/dropdown-menu.tsx` — container `rounded-xl`, items `rounded-lg`
- `frontend/src/components/ui/item.tsx` — row `rounded-xl`; `ItemMedia` image `rounded-lg` (`rounded-md` at `size=xs`)
- `frontend/src/components/ui/chart.tsx`, `tooltip.tsx` — chart tooltip `rounded-xl`, text tooltip `rounded-lg`
- `frontend/src/components/ui/progress.tsx` — `rounded-full` track
- `docs/conventions/design-system.md` — the enforced rule + Don'ts (§ 1 Shape, § 10 Don'ts)
- `docs/CODING_RULES.md` — the non-negotiable charter this rule belongs to

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Single `--radius` token, scale derived | One knob re-shapes the whole app coherently | Per-component hardcoded radii — drift, no single source of truth |
| A rung for **every** element, not just controls | Unassigned tiers are where drift happens; a lookup beats a judgment call | Controls-only rule (2026-07-12) — panels/rows/chips drifted across 3–4 radii |
| Controls at `rounded-lg`, not `rounded-full` | Pills next to `rounded-4xl` cards read as a foreign design system in a data-dense finance UI | Full-pill controls (would require pilling inputs/badges too to stay coherent) |
| Cards stay `rounded-4xl` | The round card is the app's shape signature; lowering it restyles every screen | Dropping cards to 18/22 to meet the controls |
| Radius owned by shadcn `ui/` primitives | Compose-and-inherit; pages never restyle shape | Per-page `rounded-*` overrides — the exact drift #29 introduced |

## Gotchas / Pitfalls

- **`rounded-full` on a `<Button>` className overrides the primitive.** tailwind-merge keeps the last
  radius class, so a page-level `className="… rounded-full"` silently re-pills a control even after
  `button.tsx` is correct. Fixing the primitive is not enough — the override must be removed too.
  This is how 29 pill buttons survived the 2026-07-12 fix — 27 in the setup wizard, 2 on the
  login/MFA submits — plus 5 other pilled controls.
- **`shadcn add <component>` resets the tier.** The ladder is applied by hand inside
  `components/ui/`. After regenerating any primitive, re-apply its rung from the table above.
- **`components/ui/sidebar.tsx` is deliberately untouched.** It is a vendor file with no import
  anywhere in the app (verified 2026-08-10) and still carries stock shadcn radii. Apply the ladder
  if it is ever wired up.
- **`rounded-4xl` on cards is intentional**, not drift. Do not "fix" card radius to match controls;
  cards are deliberately rounder. The controls moved up to meet them, not the other way round.
- **Regression origin — commit `716228e` (PR #29, agent-generated).** It flipped `Button`,
  `Tabs`, and `DropdownMenu` off the scale (`rounded-md/lg` → `rounded-full/2xl/xl`), hardcoded
  `rounded-full` filter chips across pages, **and rewrote `docs/conventions/frontend.md` in the same
  diff to bless pills and forbid `rounded-md`** — which made the deviation look compliant in review.
  Reverted 2026-07-12. This is why the convention-integrity rule exists in `CODING_RULES.md`.
- **Judge a new control against this document, not against the file next to it.** Before 2026-08-10
  roughly 19 hardcoded `rounded-xl`/`rounded-2xl` controls sat in `AccountForm`, `AddAccountModal`
  and `FinaryTab` selects, the `SettingsPage` / `HoldingDetailModal` / `HoldingInsightSection`
  segmented controls, `AccessKeysSection` code rows and the setup layout — so the deviation was what
  most of the app *did* while the charter said otherwise, and each new screen inherited it by
  copying a neighbouring form (that is how the property flow got it). **The sweep that applies this
  ladder clears that backlog**; it does not clear the failure mode. Copying the file next to you is
  still how drift restarts.
- **Don't hand-edit `components/ui/` for anything but an app-wide, on-scale standard.** Changing one
  primitive's radius for one screen is drift; assigning the whole app's control tier is a documented
  standard (this note + the ADR).

## Tests

- No dedicated unit test (pure styling). `frontend/src/components/layout/AppSidebar.test.tsx` and the
  Playwright E2E suite exercise the components that consume these primitives. No test asserts on a
  `rounded-*` class, so the ladder can be changed without breaking the suite — which is exactly why
  it needs the review rule below.
- Conformance is review-enforced but grep-able. Review is the only gate today (no lint rule exists),
  and the pre-2026-08-10 backlog is what it let through. This should return only ladder classes:

  ```bash
  cd frontend && grep -rhoE "rounded(-[a-z0-9]+)*(-\[[^]]+\])?" src \
    --include='*.tsx' --include='*.ts' | sort | uniq -c | sort -rn
  ```

## Links

- ADR: `docs/decisions/2026-08-10-ui-radius-ladder.md` (current)
- Superseded ADR: `docs/decisions/2026-07-12-ui-controls-follow-shadcn-theme-radius.md`
- Convention: `docs/conventions/design-system.md` (§ 1 Shape, § 10 Don'ts)
- Charter: `docs/CODING_RULES.md`
- Related feature: `docs/features/theme-persistence.md` (theme tokens, dark/light)
