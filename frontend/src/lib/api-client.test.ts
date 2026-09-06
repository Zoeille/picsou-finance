import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosAdapter } from 'axios'

// zustand's `persist` (profile-store) needs a working localStorage; jsdom here
// doesn't provide one, so install a tiny in-memory shim before importing stores.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { api, isSetupRequiredResponse } = await import('./api-client')
const { useAuthStore } = await import('@/stores/auth-store')
const { useProfileStore } = await import('@/stores/profile-store')

describe('isSetupRequiredResponse', () => {
  it('detects setup-required ProblemDetail code responses', () => {
    expect(isSetupRequiredResponse(503, {
      code: 'setup_required',
      detail: 'Picsou is not configured yet.',
    })).toBe(true)
  })

  it('keeps compatibility with setup-required detail responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'setup_required' })).toBe(true)
  })

  it('ignores generic 503 responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'Connector unavailable' })).toBe(false)
  })

  it('requires a 503 status', () => {
    expect(isSetupRequiredResponse(502, { code: 'setup_required' })).toBe(false)
  })
})

/**
 * The request interceptor must only attach `?memberId=` for admins (the backend
 * ignores the override for non-admins and rejects it for activated members). This
 * stops a stale persisted `activeMemberId` on a shared browser from ever scoping a
 * regular member's requests to someone else's data.
 */
describe('api-client memberId interceptor', () => {
  let captured: Record<string, unknown> | undefined

  beforeEach(() => {
    captured = undefined
    // Capture the outgoing params instead of hitting the network.
    const echoAdapter: AxiosAdapter = async (config) => {
      captured = config.params
      return {
        data: null,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    api.defaults.adapter = echoAdapter
  })

  afterEach(() => {
    useAuthStore.getState().logout()
    useProfileStore.getState().reset()
    window.history.replaceState(null, '', '/')
  })

  function asUser(role: 'ADMIN' | 'MEMBER') {
    useAuthStore.getState().login({ username: 'u', role, memberId: 1, displayName: 'U' })
  }

  it('attaches memberId when an admin is impersonating a managed profile', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBe(5)
  })

  it('does NOT attach memberId for a non-admin, even with a stale activeMemberId', async () => {
    asUser('MEMBER')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does NOT attach memberId when no profile is active', async () => {
    asUser('ADMIN')
    useProfileStore.getState().reset()
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does NOT attach memberId when an identity operation opts out', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    await api.delete('/me', { skipMemberOverride: true })
    expect(captured?.memberId).toBeUndefined()
  })

  it('does not redirect global 5xx errors when a GET opts out', async () => {
    const href = window.location.href
    api.defaults.adapter = async (config) => Promise.reject({
      config,
      response: { status: 502, data: { detail: 'Connector unavailable' } },
    })

    await expect(
      api.get('/sync/institutions', { params: { query: 'boursobank' }, skipGlobalErrorRedirect: true }),
    ).rejects.toMatchObject({ response: { status: 502 } })

    expect(window.location.href).toBe(href)
  })

  it('does not refresh or replay a destructive request when re-authentication fails', async () => {
    let deleteCalls = 0
    let refreshCalls = 0
    api.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        return {
          data: null,
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        }
      }

      deleteCalls += 1
      return Promise.reject({
        config,
        response: {
          status: 401,
          data: { code: 'REAUTH_FAILED' },
        },
      })
    }

    await expect(api.delete('/me', {
      data: { reAuth: { password: 'wrong' } },
    })).rejects.toMatchObject({ response: { status: 401 } })

    expect(deleteCalls).toBe(1)
    expect(refreshCalls).toBe(0)
  })

  it('rejects every queued request when the shared refresh fails', async () => {
    let resourceCalls = 0
    let refreshCalls = 0
    let releaseRefresh = () => {}
    const refreshGate = new Promise<void>(resolve => {
      releaseRefresh = resolve
    })
    window.history.replaceState(null, '', '/login')

    api.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        await refreshGate
      } else {
        resourceCalls += 1
      }
      return Promise.reject({
        config,
        response: { status: 401, data: {} },
      })
    }

    const first = api.get('/first')
    await vi.waitFor(() => expect(refreshCalls).toBe(1))
    const second = api.get('/second')
    await vi.waitFor(() => expect(resourceCalls).toBe(2))
    await new Promise(resolve => setTimeout(resolve, 0))
    releaseRefresh()

    const completion = await Promise.race([
      Promise.allSettled([first, second]).then(results => ({ results })),
      new Promise<{ results: null }>(resolve => {
        setTimeout(() => resolve({ results: null }), 500)
      }),
    ])

    expect(completion.results).not.toBeNull()
    if (completion.results === null) return
    expect(completion.results).toHaveLength(2)
    expect(completion.results.every(result => result.status === 'rejected')).toBe(true)
    expect(refreshCalls).toBe(1)
    expect(resourceCalls).toBe(2)
  })

  it('replays queued requests at most once after a shared refresh', async () => {
    const resourceCalls = new Map<string, number>()
    let refreshCalls = 0
    let releaseRefresh = () => {}
    const refreshGate = new Promise<void>(resolve => {
      releaseRefresh = resolve
    })

    api.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        await refreshGate
        return {
          data: null,
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        }
      }

      const url = config.url ?? ''
      resourceCalls.set(url, (resourceCalls.get(url) ?? 0) + 1)
      return Promise.reject({
        config,
        response: { status: 401, data: {} },
      })
    }

    const first = api.get('/first')
    await vi.waitFor(() => expect(refreshCalls).toBe(1))
    const second = api.get('/second')
    await vi.waitFor(() => expect(resourceCalls.get('/second')).toBe(1))
    await new Promise(resolve => setTimeout(resolve, 0))
    releaseRefresh()

    const results = await Promise.allSettled([first, second])
    expect(results.every(result => result.status === 'rejected')).toBe(true)
    expect(refreshCalls).toBe(1)
    expect(resourceCalls.get('/first')).toBe(2)
    expect(resourceCalls.get('/second')).toBe(2)
  })
})
