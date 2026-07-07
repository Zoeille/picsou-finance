import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { AccountCard } from './AccountCard'
import type { Account } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'fr' } }),
}))

/**
 * Radix's Avatar detects load failure via a synthetic `new Image()` instance,
 * not the rendered <img> element -- stub the global so tests can drive both
 * the success and failure paths deterministically.
 */
class MockImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  private _src = ''
  private listeners: Record<string, Array<() => void>> = {}
  addEventListener(type: string, cb: () => void) {
    ;(this.listeners[type] ??= []).push(cb)
  }
  removeEventListener(type: string, cb: () => void) {
    this.listeners[type] = (this.listeners[type] ?? []).filter((l) => l !== cb)
  }
  private emit(type: 'load' | 'error') {
    if (type === 'load') this.onload?.()
    else this.onerror?.()
    for (const cb of this.listeners[type] ?? []) cb()
  }
  set src(value: string) {
    this._src = value
    queueMicrotask(() => {
      this.emit(value.includes('broken') ? 'error' : 'load')
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

  it('falls back to the colored circle if the logo image fails to load', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/broken.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      expect(container.querySelector('img')).not.toBeInTheDocument()
      const dot = container.querySelector('[style*="background-color"]')
      expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
    })
  })
})
