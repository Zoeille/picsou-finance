# ADR: One radius ladder for every surface, not just controls

> Date: 2026-08-10
> Status: ✅ Active

## Context

[ADR 2026-07-12](./2026-07-12-ui-controls-follow-shadcn-theme-radius.md) settled the *shape question
for interactive controls*: no pills, follow the `--radius` scale, `rounded-md` for controls and
`rounded-lg` for their containers. That decision held and is not being reopened.

What it did **not** cover is everything between a control and a card: callout boxes, code blocks,
list containers, list rows, dropzones, stat tiles, icon tiles, skeletons, checkboxes, chart
tooltips. Those had no assigned rung, so each page picked one. An audit of `frontend/src` on
2026-08-10 found the same kind of element rendered at three or four different radii:

| Element | Radii found in the codebase |
|---------|-----------------------------|
| Inline error/alert strip | `rounded-md` (8), `rounded-lg` (10), `rounded-xl` (14) |
| Segmented control wrapper / item | `2xl`+`xl` (18/14), `lg`+`md` (10/8), bare `md` with no wrapper |
| Select / text input | `rounded-md` (8) from the primitive, `rounded-xl` (14) hand-written |
| Code-copy value row | `rounded` (4), `rounded-lg` (10), `rounded-xl` (14) |
| Checkbox | `rounded` (4), `rounded-sm` (6), `rounded-md` (8) |
| Buttons | `rounded-md` (8) — plus 29 overriding to `rounded-full`, and 5 more pilled controls |

The 2026-07-12 ADR predicted this failure mode ("Override vigilance … we accept relying on the
convention Don'ts and review to catch these"). It did not hold: 29 `rounded-full` button overrides
(27 in the setup wizard, 2 on the login/MFA submits) plus 5 other pilled controls — both password
eye toggles, the setup status chip, the skip link, and the setup intro text button — survived, and the unassigned tiers drifted freely because
no rule covered them.

The visible symptom, reported by the maintainer with screenshots: on one screen a 26px card sits
next to an 8px button, a 10px segmented control, and a 14px code row — four different corner radii
with no relationship between them.

## Decision

**Every box in the UI takes its radius from one ladder, keyed on what the box *is*.** The `--radius`
token and the derived scale in `index.css` are unchanged (6 / 8 / 10 / 14 / 18 / 22 / 26 px); only
the tier assignment is now defined for the whole app rather than for controls alone.

| Tier | Token | ≈ px | What |
|------|-------|------|------|
| **Surface** | `rounded-4xl` | 26 | `Card`, `DialogContent`, bottom sheet, desktop sidebar shell |
| **Panel** | `rounded-2xl` | 18 | boxes inside a surface: callouts, `<pre>` code blocks, dropzones, empty states, bordered sub-panels, stat blocks, the mobile nav bar |
| **Row / control container** | `rounded-xl` | 14 | list containers and list rows, nav rows, inline alert strips, segmented-control wrappers, dropdown/popover content, chart tooltips |
| **Control** | `rounded-lg` | 10 | button, input, select, textarea, segment item, menu item, OTP slot, icon tile (`size-8`…`size-12`), single-line code-copy value |
| **Micro** | `rounded-md` | 8 | skeleton default, `size-6` tile, treemap cell, small overlay chip |
| **Hairline** | `rounded-sm` | 6 | checkbox, `size-4` logo |
| **Circular** | `rounded-full` | — | avatar, switch, badge, status dot, progress bar, step bubble, color swatch, decorative empty-state icon bubble |
| **Chart mark** | `rounded-[2px]` | 2 | chart swatches, legend squares, thin bar segments, tooltip arrow (≤ 10px marks) |

Two consequences change previously-documented values:

1. **Controls move one rung up**, `rounded-md` → `rounded-lg`, and their containers `rounded-lg` →
   `rounded-xl`. This makes the segmented control concentric — a `p-1` (4px) wrapper at 14px holding
   a 10px item is exactly right, where 10px holding 8px was not — and narrows the gap to the 26px
   cards the controls sit next to.
2. **`DialogContent` moves `rounded-xl` → `rounded-4xl`.** A modal is a surface; it now matches the
   cards behind it instead of reading as a large panel.

Tier assignment is owned by the shadcn `ui/` primitives. App code composes `<Button>` / `<Tabs>` /
`<Card>` / `<Item>` and inherits; it must not restate or override a radius that the primitive
already provides.

## Alternatives considered

### A. Leave the primitives alone; only fix app-level overrides

- **Pros**:
  - Zero edits under `components/ui/`, fully aligned with [`CODING_RULES.md`](../CODING_RULES.md) rule 1
  - Smallest diff; `shadcn add` regenerations stay clean
- **Cons**:
  - Leaves controls at 8px next to 26px cards — the exact mismatch that was reported
  - Leaves the segmented control non-concentric (10px wrapper, 8px item, 4px padding)
  - Fixes the drift but not the underlying complaint

### B. Lower the card tier instead (26 → 18 or 22) to meet the controls

- **Pros**:
  - Also closes the gap, without touching a single control
  - Yields an evenly spaced 10 / 14 / 18 / 22 ladder
- **Cons**:
  - Restyles every screen in the app; the round card is Picsou's most visible shape signature
  - [ADR 2026-07-12](./2026-07-12-ui-controls-follow-shadcn-theme-radius.md) explicitly records
    `rounded-4xl` cards as intentional and warns against "fixing" them
  - The request was harmonisation, not a redesign

### C. Raise `--radius` so every rung grows

- **Pros**:
  - One-line change, no primitive edits
- **Cons**:
  - Scales the whole ladder uniformly — the *relative* mismatch between a control and a card is
    unchanged, which is the actual problem
  - Inflates cards past 26px as a side effect

### D. Full ladder with controls one rung up — **chosen**

- **Pros**:
  - Assigns a tier to every element, so there is nothing left to drift into
  - Concentric by construction: container radius = item radius + padding at each nesting step
  - Keeps the card signature and the no-pills principle from the previous ADR intact
- **Cons**:
  - Requires on-scale edits to twelve `components/ui/` primitives
  - Diverges from stock shadcn defaults, so a future `shadcn add` will reset those files

## Reasoning

Options A and C do not solve the reported problem — one fixes the wrong half, the other scales the
mismatch instead of removing it. That leaves a choice between moving the cards down (B) or the
controls up (D), and the previous ADR already ruled on that: cards are deliberately rounder and are
not to be "fixed" to match controls. Moving controls up is the only direction left, and it happens
to be the one that makes the nesting math work — 10 inside 14 inside 18 inside 26, each step
matching the padding that separates it from its parent.

Extending the ladder to *every* tier, rather than only to controls, is what actually prevents
recurrence. The 2026-07-12 ADR relied on review vigilance for the tiers it left unspecified, and the
audit above is the measurement of how well that worked. A rung for every element turns "what radius
should this box have?" from a judgment call into a lookup.

## Trade-offs accepted

- **`components/ui/` is hand-edited.** [`CODING_RULES.md`](../CODING_RULES.md) rule 1 forbids editing
  primitives *off their scale*; these edits stay on the `--radius`-derived scale and are an app-wide
  standard, which [`conventions/frontend.md`](../conventions/frontend.md) sanctions — "App-wide
  primitive standards (control sizing, the radius ladder) may live there when the change
  deliberately applies across the whole application and is ratified in an ADR; document those
  standards in `design-system.md`" — a sentence this ADR is the ratification for. The cost is that
  `shadcn add button` will reset `button.tsx` to `rounded-md` and the tier must be re-applied.
- **Divergence from stock shadcn.** The previous ADR counted "matches the shadcn defaults" as a pro.
  We give that up for twelve files. The theme already diverges (custom `--radius` multipliers,
  `rounded-4xl` cards, `h-10`/`px-8` control sizing), so the marginal cost is small.
- **14 vs 18 on a nested box is a judgment call.** The row/panel split is keyed on whether the box
  is one control tall (row) or a multi-line block (panel). Borderline cases exist; a 4px difference
  on a nested box is not worth re-litigating per PR.
- **Still no lint rule.** Conformance remains review-enforced, but is now grep-able: any
  `rounded-*` in app code that is not in the table above is a finding.

## Consequences

This ADR ratifies the ladder; the sweep that applies it to the codebase lands as a separate
follow-up change, per [`CODING_RULES.md`](../CODING_RULES.md) rule 0 — a convention may not be
relaxed in the same diff as the deviation it legitimises. What the sweep will do:

- `frontend/src/components/ui/`: `button.tsx`, `input.tsx`, `input-otp.tsx`, `item.tsx`, `tabs.tsx`,
  `dropdown-menu.tsx`, `tooltip.tsx`, `chart.tsx`, `empty.tsx`, `partition-bar.tsx`, `progress.tsx`
  and `dialog.tsx` take the tier assignment. `card.tsx` stays unchanged (`rounded-4xl`).
- 34 `rounded-full` overrides are removed (29 on `<Button>`, 5 on other controls), along with every
  `rounded-xl` override on an `<Input>`, `<select>`, `<textarea>` or segmented item; these inherit
  from the primitive instead.
- `components/ui/sidebar.tsx` is **not** updated: it is a vendor file with no import anywhere in the
  app. If it is ever wired up, apply the ladder then.
- New rule for reviewers: a `rounded-*` class in app code is only correct if it names a tier from the
  table, and only if the primitive does not already provide it.
- Enforced by [`conventions/design-system.md`](../conventions/design-system.md) (§ 1 Shape, § 10 Don'ts) and
  [`CODING_RULES.md`](../CODING_RULES.md) rules 1–2.

## Supersedes

[ADR 2026-07-12 — UI controls follow the shadcn theme radius, not a pill shape](./2026-07-12-ui-controls-follow-shadcn-theme-radius.md).

Its core ruling is **retained**: shape derives from `--radius`, `rounded-full` is reserved for
naturally circular elements, and app code never re-shapes a control. Only the specific rungs
(`rounded-md` control / `rounded-lg` container) are replaced by the ladder above, and the scope is
widened from controls to every surface.

## Related features

- [UI radius ladder](../features/ui-control-shape-system.md) — the implementation detail and gotchas
- [Theme (dark / light / system)](../features/theme-persistence.md) — the theme tokens these radii derive from
