import { describe, it, expect } from 'vitest'
import { PROVIDER_LOGOS, providerLogoUrl } from './provider-logos'

/**
 * Vite expands this glob against the filesystem at transform time, so the keys are exactly
 * the image assets under `public/`. The loaders are never called — matching the paths is
 * the whole point, and it keeps the app's `node`-free tsconfig (`types: ["vite/client"]`)
 * as-is instead of pulling `node:fs` into a test that lives under `src/`.
 */
const bundledAssets = new Set(
  Object.keys(import.meta.glob('../../public/**/*.{svg,png}')).map((p) =>
    p.replace('../../public', ''),
  ),
)

describe('PROVIDER_LOGOS', () => {
  // A mapped-but-missing asset is invisible at runtime: Radix's Avatar just falls back to the
  // account's color circle, which is exactly what the card showed before the mapping existed.
  // This is the only thing that turns that silence into a failure.
  it.each(Object.entries(PROVIDER_LOGOS))('ships the asset mapped for %s', (provider, path) => {
    expect(path.startsWith('/'), `${provider}: path must be root-relative`).toBe(true)
    expect(bundledAssets, `missing frontend/public${path}`).toContain(path)
  })
})

describe('providerLogoUrl', () => {
  it('resolves the exchange name the backend writes as provider', () => {
    expect(providerLogoUrl('MERIA')).toBe('/exchanges/meria.svg')
  })

  // Copied verbatim from AmundiSyncService.PROVIDER. If either side is renamed — or the
  // accent drifts between Unicode normalisations — this fails instead of quietly showing
  // the color circle again.
  it('resolves the provider string the Amundi connector writes', () => {
    expect(providerLogoUrl('Amundi Épargne Salariale')).toBe('/providers/amundi.png')
  })

  it('resolves the exchange name the backend writes as provider', () => {
    expect(providerLogoUrl('MERIA')).toBe('/exchanges/meria.svg')
  })

  it('matches case-insensitively', () => {
    expect(providerLogoUrl('Meria')).toBe('/exchanges/meria.svg')
    expect(providerLogoUrl('AMUNDI ÉPARGNE SALARIALE')).toBe('/providers/amundi.png')
    expect(providerLogoUrl('Meria')).toBe('/exchanges/meria.svg')
  })

  it('returns null for a provider with no bundled logo', () => {
    expect(providerLogoUrl('Bourse Direct')).toBeNull()
    expect(providerLogoUrl('BNP Paribas')).toBeNull()
    expect(providerLogoUrl(null)).toBeNull()
    expect(providerLogoUrl('')).toBeNull()
  })
})
