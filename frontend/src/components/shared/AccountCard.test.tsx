import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { AccountCard } from './AccountCard'
import type { Account } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

/**
 * Radix's Avatar detects load failure via a synthetic `new Image()` instance,
 * not the rendered <img> element -- stub the global so tests can drive both
 * the success and failure paths deterministically.
 *
 * Its `load` handler reads `event.currentTarget` and re-derives the status from
 * `complete`/`naturalWidth`, so listeners must be called with an event-shaped
 * argument -- calling them bare throws inside Radix instead of failing the assertion.
 */
class MockImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  complete = false
  naturalWidth = 0
  private listeners = new Map<string, Set<(event: { currentTarget: MockImage }) => void>>()
  private _src = ''

  addEventListener(type: string, listener: (event: { currentTarget: MockImage }) => void) {
    const listeners = this.listeners.get(type) ?? new Set()
    listeners.add(listener)
    this.listeners.set(type, listeners)
  }

  removeEventListener(type: string, listener: (event: { currentTarget: MockImage }) => void) {
    this.listeners.get(type)?.delete(listener)
  }

  set src(value: string) {
    this._src = value
    this.complete = false
    this.naturalWidth = 0
    queueMicrotask(() => {
      this.complete = true
      if (value.includes('broken')) {
        this.naturalWidth = 0
        this.onerror?.()
        this.listeners.get('error')?.forEach(listener => listener({ currentTarget: this }))
      } else {
        this.naturalWidth = 1
        this.onload?.()
        this.listeners.get('load')?.forEach(listener => listener({ currentTarget: this }))
      }
    })
  }
  get src() {
    return this._src
  }
}

beforeEach(() => {
  vi.stubGlobal('Image', MockImage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const baseAccount: Account = {
  id: 1,
  name: 'Compte Courant BNP',
  type: 'CHECKING',
  provider: 'BNP Paribas',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: null,
  isManual: false,
  color: '#6366f1',
  ticker: null,
  logoUrl: null,
  logoKey: null,
  createdAt: '2024-01-01T00:00:00Z',
}

describe('AccountCard', () => {
  it('renders a colored circle when the account has no logo', () => {
    const { container } = render(<AccountCard account={baseAccount} />)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    const dot = container.querySelector('[style*="background-color"]')
    expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
  })

  it('renders the bank logo image when logoUrl loads successfully', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
    })
  })

  it.each([
    ['MERIA', '/exchanges/meria.svg'],
    ['Amundi Épargne Salariale', '/providers/amundi.png'],
  ])('renders the bundled logo for %s, which the connector gives no logoUrl for', async (provider, asset) => {
    const account = { ...baseAccount, provider, logoUrl: null }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', asset)
    })
  })

  it('prefers the connector-supplied logoUrl over a bundled one', async () => {
    const account = { ...baseAccount, provider: 'MERIA', logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
    })
  })

  // A wallet's provider is its native ticker (BTC, SOL...), so nothing in PROVIDER_LOGOS can
  // match it -- the key stored on the account is the only thing that gives it a logo.
  it.each([
    ['blockchain', '/wallets/blockchain.svg'],
    ['ledger', '/wallets/ledger.svg'],
  ])('renders the %s asset for a wallet carrying that logoKey', async (logoKey, asset) => {
    const account = { ...baseAccount, type: 'CRYPTO' as const, provider: 'BTC', logoKey }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', asset)
    })
  })

  it("prefers the account's own logoKey over both a connector logo and a bundled provider one", async () => {
    const account = {
      ...baseAccount,
      provider: 'MERIA',
      logoUrl: 'https://logos.example/bnp.png',
      logoKey: 'ledger',
    }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', '/wallets/ledger.svg')
    })
  })

  it('ignores a logoKey this build has no asset for, falling through to the provider logo', async () => {
    const account = { ...baseAccount, provider: 'MERIA', logoKey: 'trezor' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', '/exchanges/meria.svg')
    })
  })

  it('falls back to the colored circle if the logo image fails to load', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/broken.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      expect(container.querySelector('img')).not.toBeInTheDocument()
      const dot = container.querySelector('[style*="background-color"]')
      expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
    })
  })

  describe('stale sync badge', () => {
    it('flags a non-manual account not synced for more than 48h', async () => {
      const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, lastSyncedAt: threeDaysAgo }
      const { findByText } = render(<AccountCard account={account} />)

      expect(await findByText('accounts.syncStale')).toBeInTheDocument()
    })

    it('shows the normal last-sync line for a recently synced account', async () => {
      const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, lastSyncedAt: oneHourAgo }
      const { queryByText, findByText } = render(<AccountCard account={account} />)

      expect(await findByText(/accounts\.lastSync/)).toBeInTheDocument()
      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })

    it('never flags a manual account', () => {
      const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, isManual: true, lastSyncedAt: threeDaysAgo }
      const { queryByText } = render(<AccountCard account={account} />)

      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })
  })
})
