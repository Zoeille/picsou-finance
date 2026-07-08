import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { FamilyMemberItem } from '@/features/family/api'

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (key) => m.get(key) ?? null,
    setItem: (key, value) => void m.set(key, String(value)),
    removeItem: (key) => void m.delete(key),
    clear: () => m.clear(),
    key: (index) => [...m.keys()][index] ?? null,
    get length() { return m.size },
  } as Storage
}

vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { listMembers } = vi.hoisted(() => ({ listMembers: vi.fn() }))

vi.mock('@/features/family/api', () => ({
  familyApi: {
    listMembers,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

const { AppSidebar } = await import('./AppSidebar')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')
const { useProfileStore } = await import('@/stores/profile-store')

function member(overrides: Partial<FamilyMemberItem>): FamilyMemberItem {
  return {
    id: 2,
    displayName: 'Lou',
    avatarColor: '#2563eb',
    managed: true,
    hasLogin: false,
    activated: false,
    loginName: null,
    mfaEnabled: false,
    ...overrides,
  }
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderSidebar(queryClient = makeClient()) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }

  render(<AppSidebar />, { wrapper: Wrapper })
  return queryClient
}

function openProfileMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: 'nav.switchProfile' }), {
    button: 0,
    ctrlKey: false,
  })
}

describe('AppSidebar profile switcher', () => {
  beforeEach(() => {
    listMembers.mockReset()
    listMembers.mockResolvedValue([])
    useAuthStore.getState().logout()
    useAppStore.getState().setDemoMode(false)
    useProfileStore.getState().reset()
  })

  it('does not load family members for non-admin users', async () => {
    useAuthStore.getState().login({ username: 'robin', role: 'MEMBER', memberId: 7, displayName: 'Robin' })

    renderSidebar()

    expect(screen.getByRole('link', { name: /Robin/ })).toHaveAttribute('href', '/settings')
    expect(screen.queryByRole('button', { name: 'nav.switchProfile' })).not.toBeInTheDocument()
    expect(listMembers).not.toHaveBeenCalled()
  })

  it('lets an admin switch to a managed profile and back to their own account', async () => {
    listMembers.mockResolvedValue([
      member({ id: 2, displayName: 'Lou', managed: true, activated: false }),
      member({ id: 3, displayName: 'Maya', managed: true, hasLogin: true, activated: true }),
    ])
    useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloe' })
    const queryClient = renderSidebar()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await screen.findByText('Chloe')
    expect(listMembers).toHaveBeenCalledTimes(1)

    openProfileMenu()
    expect(await screen.findByRole('menuitemradio', { name: /Lou/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitemradio', { name: /Maya/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('menuitemradio', { name: /Lou/ }))

    await waitFor(() => expect(useProfileStore.getState().activeMemberId).toBe(2))
    expect(invalidateSpy).toHaveBeenCalled()
    expect(await screen.findByText('Lou')).toBeInTheDocument()

    openProfileMenu()
    fireEvent.click(await screen.findByRole('menuitemradio', { name: /Chloe/ }))

    await waitFor(() => expect(useProfileStore.getState().activeMemberId).toBeNull())
    expect(invalidateSpy).toHaveBeenCalledTimes(2)
  })
})
