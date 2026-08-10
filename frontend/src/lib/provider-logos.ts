/**
 * Brand logos that ship with the app, keyed by `Account.provider`.
 *
 * Enable Banking accounts get a real `logoUrl` from its institution catalog and never reach
 * this map (see `docs/features/bank-logos.md`). Every other connector — crypto exchanges,
 * Amundi, Trade Republic, Bourse Direct... — is integrated by hand against a single provider,
 * so there is no catalog to ask for a logo and the asset ships with the frontend instead.
 *
 * Keys are the exact `provider` string the backend writes, matched case-insensitively. For
 * crypto exchanges that string is `ExchangeType.name()` (`CryptoExchangeSyncService.resolveAccount`),
 * hence `MERIA` rather than `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, accent and
 * all. `provider-logos.test.ts` pins both literals so a rename on either side fails loudly
 * instead of silently dropping the logo.
 *
 * Assets live in `public/` rather than being imported from `src/assets/` like the app's own
 * logo: a missing file then degrades to the account's color circle — exactly what every
 * logo-less account already shows — instead of failing the build. `provider-logos.test.ts`
 * is what keeps an entry from silently pointing at nothing. SVG is preferred where the
 * brand publishes one; a raster asset is fine as long as it is square and roughly 256px+,
 * since the avatar renders it at 40px with `object-contain`.
 */
export const PROVIDER_LOGOS: Record<string, string> = {
  MERIA: '/exchanges/meria.svg',
  'AMUNDI ÉPARGNE SALARIALE': '/providers/amundi.png',
}

/** The bundled logo for a provider, or `null` when none ships with the app. */
export function providerLogoUrl(provider: string | null | undefined): string | null {
  if (!provider) return null
  return PROVIDER_LOGOS[provider.toUpperCase()] ?? null
}
