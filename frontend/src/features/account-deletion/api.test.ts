import { describe, it, expect, vi } from 'vitest'

const { apiDelete, apiGet } = vi.hoisted(() => ({ apiDelete: vi.fn(), apiGet: vi.fn() }))
vi.mock('@/lib/api-client', () => ({ api: { delete: apiDelete, get: apiGet } }))

const { accountDeletionApi } = await import('./api')

describe('accountDeletionApi', () => {
  it('sends the re-auth payload in the DELETE body', async () => {
    apiDelete.mockResolvedValue({ data: { mode: 'DELETE_ACCOUNT' } })

    await expect(accountDeletionApi.deleteMe({ reAuth: { password: 's3cret' } }))
      .resolves.toEqual({ mode: 'DELETE_ACCOUNT' })

    expect(apiDelete).toHaveBeenCalledWith('/me', {
      data: { reAuth: { password: 's3cret' } },
      skipMemberOverride: true,
    })
  })

  it('supports TOTP re-auth', async () => {
    apiDelete.mockResolvedValue({ data: { mode: 'RESET_LAST_ADMIN' } })

    await accountDeletionApi.deleteMe({ reAuth: { totpCode: '123456' } })

    expect(apiDelete).toHaveBeenCalledWith('/me', {
      data: { reAuth: { totpCode: '123456' } },
      skipMemberOverride: true,
    })
  })

  it('loads the advisory deletion impact without a member override', async () => {
    apiGet.mockResolvedValue({ data: { mode: 'RESET_LAST_ADMIN' } })

    await expect(accountDeletionApi.getImpact())
      .resolves.toEqual({ mode: 'RESET_LAST_ADMIN' })

    expect(apiGet).toHaveBeenCalledWith('/me/deletion-impact', {
      skipMemberOverride: true,
    })
  })
})
