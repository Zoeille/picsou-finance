# Feature: Navigation (Sidebar + Mobile Bottom Nav)

> Last updated: 2026-07-07

## Context

Navigation adapts to screen size: a vertical sidebar on desktop (>=768px) and a horizontal bottom navbar on mobile (<768px). Desktop shows the primary app navigation, including Family, and exposes settings through the bottom account area. Admin users also get a visible profile switcher there so a managed-member override can never stay hidden. Mobile keeps the compact 4-item bottom bar and active state logic.

## How it works

### Desktop: `AppSidebar` (hidden on mobile via `hidden md:flex`)

The sidebar lives in `AppSidebar.tsx` with two visual sections:

1. **Primary nav items** — rendered by the `NavItem` internal component, one per route. Uses `react-router-dom`'s `NavLink` with `useLocation` for active detection. Desktop intentionally does not render a separate Settings item because the bottom account link already targets `/settings`.
2. **Bottom account area** — non-admin and demo sessions keep a bottom-pinned `NavLink` to `/settings`. Admin sessions render a `DropdownMenu` trigger in the same place. The trigger shows the currently scoped profile; the menu lets the admin switch back to their own account, switch into managed profiles from `selectSwitchableMembers()`, or open Settings.

Desktop navigation starts directly with the route list; there is no brand logo in the sidebar.

### Admin profile switching

The admin switcher is backed by `profile-store.activeMemberId`. Selecting a managed profile calls `setActiveMember(member.id)` and invalidates TanStack Query so the current page refetches under the new `?memberId=` scope. Selecting the admin's own account clears the override with `setActiveMember(null)`.

The switcher loads family members through `useFamilyMembers({ enabled: canSwitchProfile })`, where `canSwitchProfile = !demoMode && user?.role === 'ADMIN'`. It must stay disabled for non-admin and demo sessions because `/family/members` is admin-only and a regular member would otherwise be redirected to the global 403 page.

### Mobile: `MobileBottomNav` (hidden on desktop via `md:hidden`)

A fixed bottom bar with the Picsou logo centered and 2 nav items on each side:

```
[Dashboard] [Accounts] [LOGO] [Goals] [Settings]
```

- Same icon styling as the sidebar: `size-10 rounded-lg bg-muted text-muted-foreground`
- Active state: `ring-1 ring-border` + foreground icon color
- Safe area padding for iOS notch: `env(safe-area-inset-bottom)`
- `AppLayout` adds `pb-20 md:pb-0` on main content to avoid overlap

### Active state (shared pattern)

Active nav items keep Lucide icons stroke-only. The item gets `ring-1 ring-border` when active, and the icon box switches from `text-muted-foreground` to `text-foreground`. Do not use SVG fill for active nav states.

### Key files

- `frontend/src/components/layout/AppSidebar.tsx` — desktop sidebar with `NavItem`, admin profile switcher, and bottom-pinned account/settings access
- `frontend/src/components/layout/MobileBottomNav.tsx` — mobile bottom navbar
- `frontend/src/components/layout/AppLayout.tsx` — renders sidebar (desktop) + bottom nav (mobile)
- `frontend/src/components/ui/item.tsx` — `Item` / `ItemMedia` / `ItemContent` primitives (do not edit)
- `frontend/src/i18n/locales/{fr,en}.json` — `nav.*` keys for labels
- `frontend/src/assets/picsou_logo_white.svg` — icon-only logo used in mobile nav

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Stroke-only Lucide icons for active state | Preserves the intended outline glyphs across Dashboard, Accounts, Goals, Settings, and Family | Filled Lucide icons (`fill="currentColor"`) — fills internal shapes and creates blob-like active icons |
| Standalone `Avatar` instead of `ItemMedia` wrapper | Avoids nested box-in-box (avatar inside icon container) | Wrapping avatar in `ItemMedia` adds redundant sizing layer |
| Separate `MobileBottomNav` component | Keeps mobile/desktop nav logic isolated; each can evolve independently | One component with responsive internals — harder to read and maintain |
| 768px breakpoint (`md:`) | Consistent with existing `useIsMobile` hook and shadcn UI sidebar pattern | Custom breakpoint — would diverge from the rest of the codebase |

## Gotchas / Pitfalls

- **Babel spread in JSX props**: `{...(condition && { prop: value })}` causes a Vite/Babel parse error (500) in this project's config. Use a ternary `prop={condition ? value : fallback}` instead.
- **Lucide icons are stroke icons**: do not pass `fill="currentColor"` to sidebar or bottom-nav icons. It fills the internal SVG geometry and makes active icons look like broken blobs.
- **File encoding**: Editing `AppSidebar.tsx` with the Edit tool introduced invisible characters that broke Babel parsing. If the file breaks after edits, rewrite it entirely with the Write tool.
- **`ItemMedia` import**: The component is imported even though the user dropdown no longer uses it — it's still used by `NavItem`. Don't remove the import.
- **Mobile bottom nav padding**: `AppLayout` adds `pb-20` on mobile to prevent content from being hidden behind the fixed bottom nav. If the bottom nav height changes, update this value.
- **Admin switcher only**: the sidebar dropdown is only for profile scope plus Settings access. Logout, language, admin, and account controls remain centralized in Settings.
- **Admin-only members query**: keep `useFamilyMembers({ enabled: isAdmin })` on the sidebar. Calling `/family/members` for non-admins causes a 403 redirect.
- **No duplicate desktop Settings item**: desktop settings access is the bottom-pinned account row. Do not add `/settings` back to `NAV_ITEMS`, or the sidebar will show two entries for the same page.
- **`hidden md:flex` on sidebar**: The sidebar nav element uses `hidden md:flex` — not `md:block` — because it needs flexbox for its internal layout. Changing to `md:block` will break the sidebar layout.

## Tests

- `frontend/src/components/layout/AppSidebar.test.tsx` covers the admin switcher, query disabling for non-admins, managed-profile filtering, and Query invalidation on profile switch.
- Sidebar is also covered by E2E via Playwright (`bun run test:e2e`).

## Links

- Related: `docs/features/demo-mode.md` — the dropdown shows "Demo" in demo mode
