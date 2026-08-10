# Feature: Real estate valuation

> Last updated: 2026-08-01

## Context

`REAL_ESTATE` accounts existed since V19 but were inert: a value typed in once that never
moved, with a metadata endpoint no client ever called. This feature makes a property describe
itself (Finary-parity field set), estimate its own current value from French open data, and
report gross/net equity against the mortgages financing it.

Everything runs on **unauthenticated, Licence Ouverte 2.0** sources — no API key, no signup,
no subscription. See [the ADR](../decisions/2026-08-01-open-data-property-valuation.md) for
why that constraint drove the whole design.

## How it works

### Flow

```
User saves the property form
   └─> PUT /accounts/{id}/real-estate
        └─> address changed? clear insee_code (forces a re-geocode)

Estimate (manual button, or monthly job)
   └─> PropertyValuationService.estimate(accountId, memberId)
        ├─ requireOwner            (a co-owner may not move the balance)
        ├─ kind estimable?         HOUSE / APARTMENT only  -> else NOT_ESTIMABLE
        ├─ living area present?                            -> else INCOMPLETE_DATA
        ├─ geocode if insee_code is null
        │     GeoplateformeGeocoder -> INSEE code, lat/lon, BAN id
        │     score < 0.4                                   -> GEOCODING_FAILED
        ├─ pick the first provider whose supports() accepts -> else UNSUPPORTED_AREA
        │     CeremaDv3fValuationProvider
        │       communes scale -> median €/m² x living area
        │       empty? fall back to departements, confidence forced LOW
        ├─ PropertyAdjustments      floor, lift, outdoor space, energy, garage, land
        ├─ InseeBdmIndexProvider    carry the DVF vintage forward to today
        ├─ upsert property_valuation on (account_id, today)
        └─ valuation_mode == ESTIMATED ? write account.currentBalance : leave it alone

Daily snapshot job (unchanged) picks up the new balance -> gain curve for free
```

### Key files

**Backend**
- `port/PropertyValuationPort.java` — estimation contract; `supports()` is how a provider
  declines an area it has no data for
- `port/GeocodingPort.java` — address → INSEE code (the join key for every price series)
- `port/HousingPriceIndexPort.java` — re-indexing ratio
- `adapter/CeremaDv3fValuationProvider.java` — DV3F market indicators
- `adapter/GeoplateformeGeocoder.java` — IGN Géoplateforme
- `adapter/InseeBdmIndexProvider.java` — INSEE BDM, SDMX-ML, 24 h cache
- `service/PropertyValuationService.java` — orchestration, persistence, balance write
- `service/PropertyAdjustments.java` — the heuristic coefficient table
- `service/RealEstateSummaryService.java` — gross / net / debt roll-up
- `controller/RealEstateController.java`, `controller/GeocodingController.java`
- `model/PropertyValuation.java`, `model/ValuationStatus.java`
- `resources/db/migration/V66__real_estate_valuation_and_ownership.sql`

**Frontend**
- `components/property/PropertyDetailSection.tsx` — mounted from `AccountDetailPage` on
  `type === 'REAL_ESTATE'`
- `components/property/PropertyMetadataForm.tsx` — RHF + zod, five sections
- `components/property/AddressAutocomplete.tsx` — debounced, proxied through the backend
- `components/property/PropertyValuationCard.tsx` — estimate, band, method disclosure
- `components/property/PropertyValuationChart.tsx` — value over time vs cost basis
- `components/property/PropertyFinancingCard.tsx` — linked loans and equity
- `components/property/RealEstateSummaryCard.tsx` — dashboard roll-up

### Data sources

| Source | Auth | Notes |
|---|---|---|
| `apidf-preprod.cerema.fr` | none | still preprod, no documented rate limit, transient 503s |
| `data.geopf.fr/geocodage` | none | 50 req/s per IP |
| `api.insee.fr/series/BDM/V1` | none | quarterly index, one series per request |

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| Persist the estimate, write the balance | Depends on two external HTTP services; recomputing per read would put a third party in every dashboard load | On-the-fly in `liveBalanceEur`, as loans do |
| Monthly refresh | DVF is published twice a year, the INSEE index is quarterly — a nightly run yields identical numbers | Daily, alongside the bank sync |
| Per-room-count series for apartments (`cod121x1..x5`) | The commune-wide flat median mixes studios with five-room apartments; the narrower bucket's median surfaces (25/45/66/85/112 m²) track room count closely | Always `cod121` |
| Garage/parking priced in m²-equivalent | A flat €12,000 is absurd in central Paris and equally absurd in a village | Fixed euro amount |
| Return a `status`, don't throw | "No data in Alsace-Moselle" is information the user needs, not a request failure | HTTP error codes for every non-estimate |
| No map dependency | Would be the first third-party UI library since the shadcn baseline, for a feature the BAN label already covers | Leaflet / MapLibre |

## Gotchas / Pitfalls

- **V66/V67 keep their numbers, below the current highest.** They were numbered above the
  crypto branch's then-V64/V65, which have since become V71/V72 (renumbered around main's own
  V64). They are deliberately *not* renumbered upward to match: they collide with nothing,
  `flyway.out-of-order` is enabled for exactly this, and V66 is already applied on running
  instances — renumbering would leave those with an applied migration Flyway cannot resolve,
  and the app would refuse to start. Verified: an instance at main's V70 takes V66/V67 out of
  order and validates cleanly.
- **Do not edit V66 or V67, not even a comment.** Flyway checksums the whole file, including
  comments, and both are already applied on running instances — an edit fails validation at
  startup on every one of them. V67 exists at all because of this rule. Corrections go here,
  not in the SQL.

- **The Cerema client needs a raised buffer.** One response carries every vintage back to
  2010 with ~200 indicator columns each — about 265 KB, just past WebClient's 256 KB default.
  Over the limit the body is never assembled and *every* commune fails. It shipped that way,
  and because the error was swallowed it surfaced as "no comparable transactions in this
  municipality", sending debugging towards the address instead of the logs. Transport
  failures now raise `ValuationProviderException` → `PROVIDER_UNAVAILABLE`, which is a
  different message from an empty market.
- **A property is never left at 0 €.** Without a valuation it falls back to its cost basis;
  0 € against a purchase price renders as a 100% loss, which reads as "your flat is
  worthless" rather than "no figure yet". The floor only ever lifts a zero — a real
  valuation, manual or estimated, is never overwritten.
- **`data.geopf.fr`, never `api-adresse.data.gouv.fr`.** The old host was decommissioned end of
  January 2026 and survives only as a cross-host 301. Any client not following redirects across
  hosts — or the day the redirect stops — breaks.
- **GeoJSON coordinates are `[longitude, latitude]`.** Reading them the other way round puts
  French addresses in the Indian Ocean, and nothing downstream would notice: the valuation
  would simply use the wrong commune's median.
- **Alsace-Moselle (57, 67, 68) and Mayotte (976) return `count: 0`, not an error.** They keep
  the *livre foncier* registry. Without the explicit department check that reads as "no recent
  sales" instead of "structurally no data".
- **INSEE multi-idBank requests 404.** The documented `id1+id2` form does not work on this
  endpoint; fetch one series at a time.
- **Cerema results come back oldest-first.** Walk the array backwards for the freshest vintage.
- **The vintage is a calendar year, treated as its midpoint** when re-indexing. Using January
  would systematically over-correct by half a year.
- **`property_type` is free text predating `PropertyKind`.** Old rows may hold anything,
  including French labels, so `PropertyKind.parse` is lenient and returns `null` rather than
  throwing.
- **DGFiP reuse conditions** forbid re-identification and search-engine indexing. Demo mode
  must therefore never ship real DVF records — a demo build is public by definition.
- **Adjustment coefficients are opinions, not a fitted model.** DVF records no floor, lift,
  garden or condition. They are bounded (multiplier clamped to [0.75, 1.25], area-equivalents
  capped) and every one is disclosed in the UI.

## Tests

- `CeremaDv3fValuationProviderTest` — vintage selection, room-count series, department
  fallback, uncovered areas, transient outages
- `ValuationAdapterWiringTest` — builds the adapters the way Spring does and serves a
  >256 KB payload over a real socket; stubbed `ClientResponse` fixtures decode with their own
  strategies, so neither the constructor nor the buffer regression is visible to them
- `GeoplateformeGeocoderTest` — INSEE mapping, coordinate order, overseas department codes
- `PropertyValuationServiceTest` — MANUAL lock, status paths, re-indexing, per-property guard
- `PropertyAdjustmentsTest` — direction, bounds, no double-counting of energy vs era
- `RealEstateSummaryServiceTest` — gross/net, multiple loans, divergent property/loan shares
- `RealEstateValuationMigrationTest` — Testcontainers; existing properties survive V66
- `PropertyValuationCard.test.tsx` — status rendering, manual mode, method disclosure

## Links

- ADR: [Estimate property value from French open data](../decisions/2026-08-01-open-data-property-valuation.md)
- Related: [Per-member ownership shares](../decisions/2026-08-01-account-ownership-shares.md)
- Related feature: [Ownership shares](account-ownership-shares.md)
