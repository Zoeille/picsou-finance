import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { holdings, prices } = vi.hoisted(() => ({
  holdings: vi.fn(),
  prices: vi.fn(),
}))

vi.mock('./api', () => ({
  accountsApi: { holdings, prices },
}))

const { useHoldingsWithLivePrices } = await import('./hooks')

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useHoldingsWithLivePrices', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
  })

  it('uses the backend EUR cost basis when enriching a foreign quote', async () => {
    holdings.mockResolvedValue([
      {
        ticker: 'US',
        name: 'US share',
        quantity: 2,
        averageBuyIn: 80,
        currentPrice: 100,
        quoteCurrency: 'USD',
        currentValueEur: 180,
        costBasisEur: 140,
        pnlEur: 40,
        pnlPercent: 28.57,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
      },
    ])
    prices.mockResolvedValue({ US: 95 })

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]).toMatchObject({
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 190,
      costBasisEur: 140,
      pnlEur: 50,
    })
    expect(result.current.data?.[0].pnlPercent).toBeCloseTo(35.714, 3)
  })
})
