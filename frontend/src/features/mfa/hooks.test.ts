import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { LoginOutcome } from './api'

// zustand's profile-store uses `persist`, which reads localStorage at module
// eval; jsdom doesn't provide one. Install in-memory shims BEFORE the stores are
// (dynamically) imported below — same constraint as api-client.test.ts.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: k => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: k => void m.delete(k),
    clear: () => m.clear(),
    key: i => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

// Mock the network layer — the hooks only care about the resolved LoginOutcome.
// vi.hoisted lets the (hoisted) vi.mock factory reference these safely.
const { loginWithRememberMe, verifyMfa, getStatus } = vi.hoisted(() => ({
  loginWithRememberMe: vi.fn(),
  verifyMfa: vi.fn(),
  getStatus: vi.fn(),
}))
vi.mock('./api', () => ({
  mfaApi: { loginWithRememberMe, verifyMfa, getStatus },
}))

const { useLoginWithRememberMe, useVerifyMfa, useMfaStatus } = await import('./hooks')
const { useAuthStore } = await import('@/stores/auth-store')
const { useProfileStore } = await import('@/stores/profile-store')

const FULL: LoginOutcome = {
  mfaRequired: false,
  username: 'chloe',
  role: 'ADMIN',
  memberId: 1,
  displayName: 'Chloé',
}

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

/** Seed a fresh client plus a "previous user" footprint to assert gets wiped. */
function seededClient() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  useProfileStore.getState().setActiveMember(5) // stale impersonation target
  queryClient.setQueryData(['dashboard', '1m'], { netWorth: 999999 }) // stale cache
  return queryClient
}

beforeEach(() => {
  useAuthStore.getState().logout()
  useProfileStore.getState().reset()
  loginWithRememberMe.mockReset()
  verifyMfa.mockReset()
  getStatus.mockReset()
})

describe('MFA status freshness', () => {
  it('refetches a warm status when a sensitive flow requires a fresh value', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 60_000 } },
    })
    queryClient.setQueryData(['mfa', 'status'], {
      enabled: false,
      enrolledAt: null,
      remainingRecoveryCodes: 0,
    })
    getStatus.mockResolvedValue({
      enabled: true,
      enrolledAt: '2026-09-03T00:00:00Z',
      remainingRecoveryCodes: 10,
    })

    const { result } = renderHook(() => useMfaStatus({ fresh: true }), {
      wrapper: makeWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.data?.enabled).toBe(true))
    expect(getStatus).toHaveBeenCalledOnce()
  })
})

describe('login client-state reset (session-bleed fix)', () => {
  it('non-MFA login wipes the prior user state, then writes the new identity', async () => {
    const queryClient = seededClient()
    loginWithRememberMe.mockResolvedValue(FULL)

    const { result } = renderHook(() => useLoginWithRememberMe(), {
      wrapper: makeWrapper(queryClient),
    })
    await act(async () => {
      await result.current.mutateAsync({ username: 'chloe', password: 'pw', rememberMe: false })
    })

    // Prior footprint gone…
    expect(useProfileStore.getState().activeMemberId).toBeNull()
    expect(queryClient.getQueryData(['dashboard', '1m'])).toBeUndefined()
    // …and the new identity is in the auth store.
    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe', memberId: 1 })
  })

  it('mfaRequired keeps prior state and writes NO identity (still mid-challenge)', async () => {
    const queryClient = seededClient()
    loginWithRememberMe.mockResolvedValue({ mfaRequired: true, username: 'chloe' } as LoginOutcome)

    const { result } = renderHook(() => useLoginWithRememberMe(), {
      wrapper: makeWrapper(queryClient),
    })
    await act(async () => {
      await result.current.mutateAsync({ username: 'chloe', password: 'pw', rememberMe: false })
    })

    // No session yet → nothing wiped client-side, no identity written. (The
    // server clears any foreign session cookies on this branch instead.)
    expect(useProfileStore.getState().activeMemberId).toBe(5)
    expect(queryClient.getQueryData(['dashboard', '1m'])).toEqual({ netWorth: 999999 })
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('MFA verification wipes the prior user state, then writes the new identity', async () => {
    const queryClient = seededClient()
    verifyMfa.mockResolvedValue(FULL)

    const { result } = renderHook(() => useVerifyMfa(), {
      wrapper: makeWrapper(queryClient),
    })
    await act(async () => {
      await result.current.mutateAsync({ code: '123456', isRecoveryCode: false, trustDevice: false })
    })

    expect(useProfileStore.getState().activeMemberId).toBeNull()
    expect(queryClient.getQueryData(['dashboard', '1m'])).toBeUndefined()
    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe', memberId: 1 })
  })
})
