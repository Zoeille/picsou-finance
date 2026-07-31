import '@testing-library/jest-dom'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ExchangePositionResponse } from '@/types/api'

vi.mock('react-i18next', () => ({
  // CurrencyDisplay reads i18n.resolvedLanguage to pick its number format.
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { PositionsByProduct } = await import('./PositionsByProduct')

const POSITIONS: ExchangePositionResponse[] = [
  { product: 'SPOT', ticker: 'BTC', quantity: 0.5, principal: null, interest: null, currentPriceEur: 100, currentValueEur: 50 },
  { product: 'STAKING', ticker: 'ATOM', quantity: 33.154, principal: 19.73, interest: 13.424, currentPriceEur: 5, currentValueEur: 165.77 },
  // Same asset, two products — the split this component exists to show.
  { product: 'STAKING', ticker: 'BTC', quantity: 0.25, principal: 0.2, interest: 0.05, currentPriceEur: 100, currentValueEur: 25 },
]

function sectionFor(product: string) {
  const heading = screen.getByText(`positions.products.${product}`)
  const section = heading.closest('div')?.parentElement
  if (!section) throw new Error(`no section for ${product}`)
  return within(section)
}

describe('PositionsByProduct', () => {
  it('groups positions per product and keeps the same asset in each', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    expect(sectionFor('SPOT').getAllByText('BTC')).toHaveLength(1)
    expect(sectionFor('STAKING').getAllByText('BTC')).toHaveLength(1)
    expect(sectionFor('STAKING').getByText('ATOM')).toBeInTheDocument()
    expect(screen.queryByText('positions.products.LENDING')).not.toBeInTheDocument()
  })

  it('shows principal and interest only where the exchange reports yield', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    // Spot has no yield decomposition: no extra columns at all.
    expect(sectionFor('SPOT').queryByText('positions.interest')).not.toBeInTheDocument()

    const staking = sectionFor('STAKING')
    expect(staking.getByText('positions.principal')).toBeInTheDocument()
    expect(staking.getByText('positions.interest')).toBeInTheDocument()
    // The three numbers the user asked for: principal, interest, and the total actually held.
    expect(staking.getByText('19.73')).toBeInTheDocument()
    expect(staking.getByText('13.424')).toBeInTheDocument()
    expect(staking.getByText('33.154')).toBeInTheDocument()
  })

  it('renders nothing when there is no breakdown', () => {
    const { container } = render(<PositionsByProduct positions={[]} />)

    expect(container).toBeEmptyDOMElement()
  })
})
