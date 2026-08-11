# Convention: Design system

> The single authority for **how Picsou looks**. Read this before writing or reviewing any UI.
>
> Scope split — this file owns *visual* decisions (shape, color, type, spacing, elevation, motion,
> component choice, states). [`frontend.md`](./frontend.md) owns *code* decisions (state management,
> hooks, API layer, routing, i18n wiring, types). When they overlap, this file wins on looks and
> `frontend.md` wins on structure. Do not restate a rule from here into another file — link to it.
>
> **Status:** this file states the standard. The radius ladder in §1 is ratified by
> [ADR 2026-08-10](../decisions/2026-08-10-ui-radius-ladder.md) and applied to the codebase by the
> follow-up sweep — until that lands, the §12 radius grep still reports pre-ladder values in app
> code. Everything else here describes what the codebase already does.

## 0. The one rule

**Compose the primitives and inherit. Never restyle.**

Picsou's look is carried by `frontend/src/components/ui/` (shadcn primitives) and
`frontend/src/index.css` (theme tokens). A page's job is to *pick the right component*, not to
re-describe what it should look like.

```tsx
// ✅ inherits shape, height, padding, focus ring, dark mode
<Button variant="outline">{t('accounts.add')}</Button>

// ❌ every class here is either a duplicate of the primitive or a deviation from it
<button className="h-9 rounded-full border px-3 text-xs hover:bg-gray-100">…</button>
```

If a class in app code sets **radius, height, font-size, or color** on something a primitive already
renders, it is a finding until proven otherwise. Adding a class the primitive does not provide
(layout, width, margin, grid placement) is normal and fine.

**Why so strict:** commit `716228e` (PR #29) restyled controls page-by-page *and* rewrote the
convention to bless it, so review passed and a human found the regression weeks later. That is the
origin of [`CODING_RULES.md`](../CODING_RULES.md) rule 0 — a PR that changes the code and the rule
together to make a deviation look compliant gets **rejected**, not merged. Land the rule change on
its own, ratified for its own sake, or drop the deviation.

---

## 1. Shape — the radius ladder

`--radius: 0.625rem` (10px) in `index.css` derives the whole scale:
`sm` 6 · `md` 8 · `lg` 10 · `xl` 14 · `2xl` 18 · `3xl` 22 · `4xl` 26 px.

**Every box picks a rung by what it is** — never by taste, never a hardcoded pixel value:

| Tier | Class | ≈ px | Applies to |
|------|-------|------|-----------|
| **Surface** | `rounded-4xl` | 26 | `Card`, `DialogContent`, bottom sheet, desktop sidebar shell |
| **Panel** | `rounded-2xl` | 18 | boxes inside a surface: callouts, `<pre>` code blocks, dropzones, empty states, bordered sub-panels, stat blocks, mobile nav bar |
| **Row / control container** | `rounded-xl` | 14 | list containers and rows, nav rows, inline alert strips, segmented-control wrappers, dropdown/popover content, chart tooltips |
| **Control** | `rounded-lg` | 10 | button, input, select, textarea, segment item, menu item, OTP slot, icon tile (`size-8`…`size-12`), single-line code-copy value |
| **Micro** | `rounded-md` | 8 | skeleton default, `size-6` tile, treemap cell, small overlay chip |
| **Hairline** | `rounded-sm` | 6 | checkbox, `size-4` logo |
| **Circular** | `rounded-full` | — | *identity* avatar (see below), switch, badge, status dot, progress bar, step bubble, color swatch, decorative empty-state icon bubble |
| **Chart mark** | `rounded-[2px]` | 2 | chart swatches, legend squares, thin bar segments, tooltip arrow (≤ 10px marks) |

**Two clarifications on that table.**

*Chart marks are the one sanctioned arbitrary value.* `rounded-[2px]` is deliberate, not drift:
below ~10px every rung on the scale reads as a circle, so a mark that small needs a value off it.
It is the only `rounded-[…]` allowed in app code.

*Avatars split by what they hold.* An avatar standing for a **person** is circular — that is the
`Avatar` primitive's default, so inherit it. An avatar used as a **brand or logo tile** takes the
icon-tile rung instead (`rounded-sm` at `size-4`, `rounded-md` at `size-6`, `rounded-lg` at
`size-8`…`size-12`), because a logo clipped to a circle loses its mark. The codebase does not apply
this split consistently yet — see §11.

**The ladder is concentric.** At every nesting step, the parent's radius = the child's radius + the
padding between them. `TabsList` is `p-1` (4px) at `rounded-xl` (14) holding a `rounded-lg` (10)
trigger. Picking a rung off the ladder breaks that alignment visibly.

**Row vs panel** is decided by height: one control tall (≈40–56px) is a *row*; a multi-line block
with `p-4`+ is a *panel*.

**Cards stay `rounded-4xl` on purpose.** Do not "fix" the card radius down to match controls — the
round card is the app's shape signature. Controls were moved *up* to meet it.

Rationale, gotchas, and the audit that produced this:
[`features/ui-control-shape-system.md`](../features/ui-control-shape-system.md) ·
ADR [2026-08-10](../decisions/2026-08-10-ui-radius-ladder.md).

---

## 2. Color

### Semantic tokens only

All tokens live in the `@theme` / `:root` / `.dark` blocks of `frontend/src/index.css`, in oklch,
with a light and a dark value each. **Never use a raw palette class** (`text-gray-*`, `bg-gray-*`) —
they bypass the theme and do not adapt to dark mode. In this project the gray palette is remapped to
blue, so `text-gray-500` renders *blue*, which is how the mistake gets caught late.

| Intent | Token |
|--------|-------|
| Page background | `bg-background` |
| Primary text | `text-foreground` |
| Muted / secondary text | `text-muted-foreground` |
| Subtle fill | `bg-muted` |
| Card / surface fill | `bg-card` (+ `text-card-foreground`) |
| Floating surface fill | `bg-popover` (+ `text-popover-foreground`) |
| Primary action | `bg-primary` / `text-primary` (+ `text-primary-foreground`) |
| Secondary action | `bg-secondary` (+ `text-secondary-foreground`) |
| Destructive | `text-destructive`, `bg-destructive/10` |
| Hairline / divider | `border-border`, `ring-border` |
| Field fill | `bg-input/20` (`dark:bg-input/30`) |
| Focus ring | `ring` (applied centrally — see §7) |
| Chart series | `--chart-1` … `--chart-5` |
| Sidebar surfaces | `--sidebar*` family |

### Status colors — the one sanctioned exception

Financial and status signals may use literal palette colors, because green/red carry meaning that no
semantic token expresses: gain/loss, sync success/failure, staleness warnings. When you do:

- Always ship a dark-mode variant (`text-emerald-500 dark:text-emerald-400`) or pick a shade that
  reads on both.
- **Gain is `emerald`, loss is `red`.** Use `emerald-500` / `red-500` as the default pair — that is
  what the portfolio, holdings, and P&L surfaces already use.
- Warning is `amber`, informational is `blue`, on-chain/violet accents belong to goals.

Never use a status color for a neutral UI element. A gray button is `variant="ghost"`, not
`text-slate-400`.

---

## 3. Typography

One family: **Geist Variable**, loaded via `@fontsource-variable/geist`, applied on `html` as
`font-sans`. `--font-heading` maps to the same family — there is no second typeface, so a "heading
font" change means weight and size, never family.

| Role | Class | Notes |
|------|-------|-------|
| Page title | `PageHeader` | Don't hand-roll — see §5 |
| Card title | `CardTitle` (`cn-card-title`: `text-sm`, weight 700) | Small and bold by design, not a bug |
| Section / card description | `CardDescription` (`text-sm`) | Do **not** shrink to `text-xs` locally |
| Body, controls, labels | `text-sm` | The workhorse — 339 uses |
| Hero figures (net worth, goal totals) | `text-2xl` … `text-4xl` + `tabular-nums` | |
| Dense metadata, chart labels, badges | `text-xs` | |
| Form `Label` | `Label` (`text-sm`) | Do **not** shrink locally |

**Numbers always get `tabular-nums`.** Any figure that can change or sits in a column — balances,
percentages, quantities, dates in tables — must not jitter as digits change.

**Money goes through `CurrencyDisplay`**, never `toFixed()` + a hardcoded `€`. It resolves the active
locale and handles the sign. Same for dates and numbers: use the `lib/utils.ts` helpers
(`formatCurrency`, `formatDate`, `getLocale`) so FR/EN/DE/ES all format correctly.

Do not use arbitrary sizes (`text-[15px]`) for new work — pick a rung. See §11 for the two existing
off-scale values and why they are grandfathered rather than copied.

---

## 4. Spacing, sizing, elevation, motion

### Control rhythm

Text inputs, buttons, tabs, menus, segmented controls and pill filters share one CTA rhythm:

- Height `h-10` · font `text-sm`
- Padding: `px-8` buttons · `px-4` inputs/selects · `px-6` dense segmented items
- Radius: the **control** rung (§1)

Avoid local `h-6`/`h-7`/`h-8`/`h-9`, `text-xs`, or narrow `px-2` on a text control. Smaller sizing is
for pure icon buttons, badges, dense table cells, chart labels, and non-interactive metadata.

Icon buttons use the `Button` `size="icon"` family (`icon-xs` 32 · `icon-sm` 36 · `icon` 40 ·
`icon-lg` 44) — never a hand-built `h-8 w-8` div with an onClick.

### Spacing scale

Stay on the 4px grid. In practice the codebase uses a narrow band, and new work should too:

| Context | Value |
|---------|-------|
| Icon ↔ label inside a control | `gap-2` |
| Related items in a row | `gap-3` |
| Card / grid gutters | `gap-4` |
| Tight stacks (label + field) | `space-y-2` |
| Form field groups | `space-y-4` |
| Page sections | `space-y-6` |
| Card padding | `px-4` (from `Card` — don't restate) |
| Panel padding | `p-4`, dense variants `p-3` |

### Icon sizes

`lucide-react` only, as direct JSX (`<Pencil className="size-4" />`). No other icon library.

`size-4` is the default (inside controls, inline with `text-sm`) · `size-5` for nav and emphasis ·
`size-3`/`size-3.5` for dense metadata · `size-10`/`size-12` for empty-state and hero illustrations.

### Elevation — rings, not shadows

Picsou separates surfaces with a **hairline ring**, not a drop shadow: `ring-1 ring-foreground/10`
(cards, dialogs), `ring-1 ring-border` (nav shells). Reserve `shadow-lg`/`shadow-xl` for genuinely
floating, transient things — chart tooltips and popovers. Do not add a shadow to a card.

### Motion

**Name the animated properties.** `transition-all` is banned in app code: it animates layout
properties you didn't intend and costs paint. Use bounded values:

```tsx
transition-[background-color,color,border-color]   // hover/active states
transition-[border-color,box-shadow]                // selection rings
transition-[width]                                  // progress bars
transition-transform                                // press feedback
transition-colors                                   // the common shorthand, fine
```

Press feedback on buttons is already in the primitive (`active:scale-[0.96]`) — don't re-add it.
Avoid `scale-*` on selected swatches or cards: dialogs and cards clip overflow, so scaled round
elements look cut off.

---

## 5. Layout

- **Pages start flush with the main content column.** No `mx-auto` centering wrapper on a top-level
  app page — `AppLayout` already owns the gutters (`md:p-4 md:gap-4`).
- **Every page opens with `PageHeader`** (`components/shared/PageHeader.tsx`), which supplies the
  date surtitle, the title, and right-aligned actions. Don't hand-roll an `<h1>`.
- **Cards are the unit of content.** Group with `Card` / `CardHeader` / `CardTitle` /
  `CardDescription` / `CardContent`. Card padding comes from the primitive.
- **Responsive grids** step `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3` with `gap-4`. The breakpoint
  for mobile/desktop chrome is `md` (768px), matching `useIsMobile` and the nav split.
- **Mobile:** the bottom nav is fixed, so scrollable content carries `pb-20 md:pb-0`. If the nav's
  height changes, update that value.

Shell shapes: the desktop sidebar is a floating **surface** (`rounded-4xl`), the mobile dock is a
compact **panel** (`rounded-2xl`). See [`features/sidebar-navigation.md`](../features/sidebar-navigation.md).

---

## 6. The four states — every data surface needs all of them

A screen that only handles the happy path is incomplete. TanStack Query gives you the flags; render
all four:

| State | Component | Rule |
|-------|-----------|------|
| **Loading** | `Skeleton` / `LoadingSkeleton` | Skeletons mirror the *shape* of what they replace — a row placeholder is `h-16 rounded-xl`, a chart is `h-[250px] rounded-2xl`, a card is `rounded-4xl` |
| **Empty** | `EmptyState` (page) · `EmptyChartState` (chart) | A page-level empty state centers in the remaining viewport height; one nested in a card or modal stays compact |
| **Error** | `ErrorState` with `onRetry` | **Render it, never hide it.** An empty card reads as "nothing to show", which is the wrong conclusion when the fetch failed. Format via `formatApiError` |
| **Degraded** | `DegradedModeBanner` | For partial backend availability |

Destructive actions go through `ConfirmDialog` — never a bare `window.confirm`, never a one-click
delete.

---

## 7. Accessibility

- **Focus is centralized.** `index.css` applies a `:focus-visible` outline + ring to every
  interactive role. Do **not** add one-off `focus:*` / `focus-visible:*` / `focus-within:*` classes —
  the only sanctioned exception is an element that must *appear* on focus, like the setup skip link.
- Hit targets follow the control rhythm: `h-10` for text controls, and for icon buttons any rung of
  the `Button` `size="icon"` family (`icon-xs` 32px … `icon-lg` 44px). All of them clear the 24px
  WCAG 2.1 AA minimum (2.5.8); reserve `icon-xs` for dense toolbars and prefer `icon` (40px) for
  anything a user hits often or on touch.
- Icon-only buttons need `aria-label` **and** `title`. Decorative icons need `aria-hidden="true"`.
- Toggles use `aria-pressed`; segmented radio groups use `role="radiogroup"` + `role="radio"` +
  `aria-checked`; live regions use `aria-live="polite"`; errors use `role="alert"`.
- Never signal state with color alone — pair it with an icon, a label, or a ring.
- **All user-visible text goes through `useTranslation()`**, in all four locales (FR/EN/DE/ES). A
  hardcoded string is a bug even if the app is currently in French.

---

## 8. Charts

Recharts v3 only. Series colors come from `--chart-1` … `--chart-5` — never hand-picked hex. Chart
tooltips are the **row** rung (`rounded-xl`) and are the one place `shadow-lg`/`shadow-xl` is
correct. Legend swatches and thin bar segments use the **chart mark** rung (`rounded-[2px]`); at
≤10px a "proper" radius reads as a circle.

Every chart needs an `EmptyChartState` fallback, axis labels in the active locale, and
`tabular-nums` on any rendered figure.

---

## 9. "I need to build X" — pick, don't invent

| You need | Use | Never |
|----------|-----|-------|
| Any button | `Button` (+ `variant`, `size`) | a styled `<button>` |
| Text field | `Input` · numbers `NumericInput` · dates `DateInput` | a styled `<input>` |
| Dropdown of options | native `<select>` on the control rhythm (see §11), or `DropdownMenu` for actions | a custom popover |
| Tabs / segmented control | `Tabs` + `TabsList` + `TabsTrigger` | a hand-built wrapper + buttons |
| Modal | `Dialog` family | a fixed-position div |
| Confirm a destructive action | `ConfirmDialog` | `window.confirm` |
| List row | `Item` + `ItemMedia`/`ItemContent`/`ItemTitle` | a flex div with a radius |
| Content grouping | `Card` family | a bordered div |
| Status pill | `Badge` · `AccountTypeBadge` | a `rounded-full` span |
| Money | `CurrencyDisplay` | `toFixed()` + `€` |
| Progress | `Progress` · `GoalProgressBar` | a div pair with widths |
| Page title | `PageHeader` | an `<h1>` |
| Empty / error / loading | §6 | a bare `null` |
| Toast | `sonner` (`toast.*`) | an inline banner |
| Color / logo choice | `ColorPicker` · `LogoPicker` | a swatch grid |

Before building a new shared component, grep `components/shared/` — there are ~40 of them.

---

## 10. Don'ts (grep-able)

- **Never edit `components/ui/`** except for an app-wide, on-scale standard that is ratified in an
  ADR and written down here. A one-screen tweak never qualifies.
- **Never restate a radius/height/color a primitive already provides** (`<Card className="rounded-4xl">`,
  `<Button className="rounded-lg">`). It is noise, and it hides the real overrides during an audit.
- **Never `rounded-full` on a text control**, and never override a primitive's radius via className —
  tailwind-merge keeps the *last* radius class, so the page silently wins over the primitive.
- **Never a raw palette class** for a neutral (`text-gray-*`, `bg-slate-*`).
- **Never `transition-all`** in app code.
- **Never inline `style={{…}}` objects, CSS modules, or styled-components.** Tailwind only. (Dynamic
  values that genuinely can't be a class — a chart series color, a computed bar width — are the
  exception, via `style={{ backgroundColor: item.color }}`.)
- **Never a hardcoded user-visible string** — `useTranslation()`, four locales.
- **Never a one-off `focus:*` ring.**
- **Never hide an error state.**
- **Never `scale-*` on a swatch or card** (overflow clipping).

---

## 11. Known deviations

Documented so the next agent doesn't propagate them as if they were the rule:

- **`PageHeader` uses `text-[28px]` / `text-[13px]` and inline `style={{ fontWeight }}`.** Off the
  type scale and against the no-inline-style rule. It is the only page title in the app, so it is
  visually consistent by construction — but do **not** copy the pattern into a new component.
- **`transition-all` survives in four `components/ui/` files** (`progress`, `switch`, `badge`, and
  the unused `sidebar`). Vendor-generated; app code is clean. Don't add more.
- **Gain green is not fully uniform** — `emerald-500` dominates (28 uses) but `green-600`/`green-400`
  appear (18). New work uses `emerald`.
- **`components/ui/sidebar.tsx` is dead** — no import anywhere in the app, still on stock shadcn
  radii. Apply the ladder only if it is ever wired up.
- **Avatar shape is split and not yet reconciled.** Person avatars are circular in `MembersSection`
  (`size-9 rounded-full`) but square in `AppSidebar`'s profile rows (`size-10`/`size-8 rounded-lg`,
  `size-6 rounded-md`) — deliberate there, since they sit beside nav icon tiles of the same size.
  Conversely `AccountCard` renders *bank logos* through a circular `Avatar` while `AddAccountModal`
  renders the same logos square (`size-4 rounded-sm`). Follow §1 for new work; normalising the four
  existing sites is a visible design change, not a radius fix, so it is deliberately out of scope.
- **`ErrorState` ships hardcoded English defaults** (`'Error'`, `'Retry'`). Pass translated props
  until it is fixed.
- **There is no shared `Select` component.** Native `<select>` elements are styled with the control
  class inline, and the same string is now copy-pasted in **7** places — `AccountForm` and
  `PropertyMetadataForm` hold it as a module-local `selectControlClassName`/`selectClassName`, while
  `AddAccountModal` ×2, `FinaryTab` ×2 and `AddPropertyModal` repeat it literally:

  ```text
  h-10 w-full rounded-lg border border-input bg-input/20 px-4 text-sm outline-none dark:bg-input/30
  ```

  This is the clearest instance of the copy-the-neighbouring-file failure mode in the codebase.
  Reuse that exact string for now, but **extracting it into a shared export is the next change this
  file is asking for** — do not add an eighth copy.

---

## 12. Conformance checks

Run these before opening a UI PR. Each should return only values named in this file:

```bash
cd frontend

# Radius — every result must be a rung from §1
grep -rhoE "rounded(-[a-z0-9]+)*(-\[[^]]+\])?" src --include='*.tsx' --include='*.ts' \
  | sort | uniq -c | sort -rn

# Raw palette leaks (should be empty)
grep -rnE "(text|bg|border)-(gray|slate|zinc|neutral|stone)-[0-9]" src --include='*.tsx'

# Unbounded transitions in app code (should be empty)
grep -rn "transition-all" src/pages src/components/shared src/components/layout --include='*.tsx'

# Pilled text controls (each hit must be an avatar/switch/badge/dot/swatch).
# Scan all of src except the generated primitives, which own the sanctioned pills.
grep -rn "rounded-full" src --include='*.tsx' | grep -v "^src/components/ui/"

# Inline style objects (each hit must be a genuinely dynamic value)
grep -rn "style={{" src/pages src/components/shared --include='*.tsx'
```

---

## Links

- Charter: [`CODING_RULES.md`](../CODING_RULES.md) — rules 0–2 are the enforcement teeth for this file
- Code conventions: [`frontend.md`](./frontend.md) — state, hooks, API, routing, i18n wiring, types
- Radius detail: [`features/ui-control-shape-system.md`](../features/ui-control-shape-system.md)
- ADR: [2026-08-10 radius ladder](../decisions/2026-08-10-ui-radius-ladder.md) (supersedes
  [2026-07-12](../decisions/2026-07-12-ui-controls-follow-shadcn-theme-radius.md))
- Theme tokens: [`features/theme-persistence.md`](../features/theme-persistence.md)
- Navigation shells: [`features/sidebar-navigation.md`](../features/sidebar-navigation.md)
