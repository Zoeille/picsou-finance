# Picsou Backend API Reference

> This document is manually maintained. When adding or changing an endpoint, update this file accordingly.

## Overview

| Property | Value |
|----------|-------|
| Base URL | `/api` |
| Content-Type | `application/json` (except multipart endpoints noted below) |
| Authentication | JWT via HttpOnly cookies (`access_token` + `refresh_token`) |
| Access token TTL | 15 minutes |
| Refresh token TTL | 7 days (rotated on every use) |

### Auth flow

1. `POST /api/auth/login` — sends credentials, receives `access_token` + `refresh_token` as HttpOnly, SameSite=Strict cookies
2. All subsequent requests include cookies automatically — no header needed
3. On 401, the frontend calls `POST /api/auth/refresh` to get new tokens; the old refresh token is invalidated (rotation)
4. `POST /api/auth/logout` clears both cookies

### Rate limiting

| Endpoint group | Limit |
|---------------|-------|
| Login (`/api/auth/login`) | 5 requests / IP / 15 min |
| Bank sync (`/api/sync/initiate`) | Throttled |
| TR auth (`/api/tr/auth/initiate`) | Throttled |

## Shared Enums

### AccountType

`LEP` · `PEA` · `COMPTE_TITRES` · `CRYPTO` · `CHECKING` · `SAVINGS` · `OTHER`

### Chain

`SOLANA` · `ETHEREUM` · `BITCOIN`

### ExchangeType

`BINANCE` · `KRAKEN`

### FinaryMappingAction

`SKIP` · `MAP_EXISTING` · `CREATE_NEW`

## Error Format

All errors use [RFC 7807 ProblemDetail](https://datatracker.ietf.org/doc/html/rfc7807):

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials"
}
```

Validation errors (422) include an `errors` map:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": null,
  "errors": {
    "name": "must not be blank",
    "targetAmount": "must be greater than 0.01"
  }
}
```

| Status | When |
|--------|------|
| 400 | `IllegalArgumentException` — bad request logic |
| 401 | `BadCredentialsException` — invalid credentials or missing auth |
| 404 | `ResourceNotFoundException` — entity not found |
| 422 | Validation failure (`@Valid`) — includes `errors` map |
| 429 | Rate limit exceeded |
| 502 | `SyncException` — upstream provider error |
| 500 | Unexpected server error (message is always `"An unexpected error occurred"`) |

---

## Endpoints

---

### 1. Authentication — `/api/auth`

#### `POST /api/auth/login`

- **Auth:** Public
- **Rate limit:** 5 / IP / 15 min

**Request body:**
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `username` | `string` | @NotBlank, max 50 | |
| `password` | `string` | @NotBlank, max 128 | |

**Response `200`:**
```json
{ "username": "string" }
```
Sets `access_token` and `refresh_token` HttpOnly cookies.

**Errors:** 401 (invalid credentials), 429 (rate limited)

---

#### `POST /api/auth/refresh`

- **Auth:** Public (reads `refresh_token` cookie; also honors a valid `persistent_token` "Remember Me" cookie via `PersistentTokenAuthFilter`)
- **Body:** none

**Response `200`:**
```json
{ "username": "string", "role": "string", "memberId": 0, "displayName": "string" }
```
Rotates `access_token`/`refresh_token` (old refresh token is invalidated) whenever a valid `refresh_token` is presented, **or** when a still-valid `persistent_token` re-authenticates the request in place of a missing/invalid one (this is what lets "Remember Me" survive a tab/browser restart, since the frontend probes this endpoint on mount instead of trusting a stale client-side flag). `access_token`/`refresh_token` are reissued as **persistent cookies** (matching `persistent_token`'s remaining lifetime) only when the request actually carries a `persistent_token` owned by the same user — otherwise they're reissued as session cookies, so a non-"Remember Me" login can't outlive the browser via this endpoint. A Remember-Me `refresh_token` is bound to its persistent-session `series_id` (a `sid` claim); if that session has been revoked (`/auth/sessions`) or has passed its 90-day cap, the refresh is refused even though the JWT itself is still valid, so revoking a device actually logs it out at its next refresh.

**Errors:** 401 (no refresh token and no valid persistent_token; or the presented session's series has been revoked/expired — `"Session revoked"`)

---

#### `POST /api/auth/logout`

- **Auth:** Public
- **Body:** none

**Response `204`** — clears both cookies.

---

#### `POST /api/auth/change-password`

- **Auth:** Required

**Request body:**
| Field | Type | Constraints |
|-------|------|-------------|
| `currentPassword` | `string` | @NotBlank |
| `newPassword` | `string` | @NotBlank, min 8, max 128 |

**Response `200`:**
```json
{ "message": "Password updated successfully" }
```

**Errors:** 401 (current password incorrect), 422 (validation)

---

### 2. Dashboard — `/api/dashboard`

#### `GET /api/dashboard`

- **Auth:** Required
- **Body:** none

**Response `200` — `DashboardResponse`:**
```json
{
  "totalNetWorth": 15000.00,
  "netWorthHistory": [
    { "date": "2025-01-01", "total": 14000.00 },
    { "date": "2025-02-01", "total": 14500.00 }
  ],
  "distribution": [
    {
      "accountId": 1,
      "name": "PEA",
      "color": "#6366f1",
      "balanceEur": 8000.00,
      "percentage": 53.3
    }
  ],
  "goalSummaries": [ /* GoalProgressResponse[] — see Goals section */ ]
}
```

---

### 3. Accounts — `/api/accounts`

#### `GET /api/accounts`

- **Auth:** Required

**Response `200` — `AccountResponse[]`:**

```json
[
  {
    "id": 1,
    "name": "PEA Boursorama",
    "type": "PEA",
    "provider": null,
    "currency": "EUR",
    "currentBalance": 8000.00,
    "currentBalanceEur": 8000.00,
    "lastSyncedAt": "2025-03-15T10:30:00Z",
    "isManual": false,
    "color": "#6366f1",
    "ticker": null,
    "createdAt": "2024-06-01T08:00:00Z"
  }
]
```

---

#### `GET /api/accounts/{id}`

- **Auth:** Required

**Response `200` — `AccountResponse`** (same shape as above).

**Errors:** 404 (account not found)

---

#### `POST /api/accounts`

- **Auth:** Required

**Request body — `AccountRequest`:**
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `name` | `string` | @NotBlank, max 100 | Account name |
| `type` | `AccountType` | @NotNull | Account type enum |
| `provider` | `string` | max 100 | External provider name (optional) |
| `currency` | `string` | @NotBlank, max 10 | Currency code, e.g. `"EUR"` |
| `currentBalance` | `number` | @DecimalMin("0") | Balance in native currency |
| `isManual` | `boolean` | | Whether manually managed |
| `color` | `string` | Hex pattern | Display color, e.g. `"#6366f1"` |
| `ticker` | `string` | max 20 | Ticker for price lookup (optional) |

**Response `201` — `AccountResponse`.**

**Errors:** 422 (validation)

---

#### `PUT /api/accounts/{id}`

- **Auth:** Required
- **Body:** same `AccountRequest` as POST

**Response `200` — `AccountResponse`.**

**Errors:** 404, 422

---

#### `DELETE /api/accounts/{id}`

- **Auth:** Required
- **Body:** none

**Response `204`.**

**Errors:** 404

---

#### `GET /api/accounts/{id}/holdings`

- **Auth:** Required

**Response `200` — `HoldingResponse[]`:**
```json
[
  {
    "ticker": "AAPL",
    "name": "Apple Inc.",
    "quantity": 10,
    "averageBuyIn": 150.00,
    "currentPrice": 195.00,
    "quoteCurrency": "USD",
    "currentValueEur": 1800.00,
    "costBasisEur": 1500.00,
    "pnlEur": 300.00,
    "pnlPercent": 20.00,
    "priceUpdatedAt": "2026-07-20T10:00:00Z"
  }
]
```

`currentPrice` is expressed in `quoteCurrency`. `averageBuyIn`,
`currentValueEur`, `costBasisEur` and `pnlEur` are EUR-denominated.

---

#### `GET /api/accounts/{id}/history`

- **Auth:** Required

**Query params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `from` | `ISO-8601 date` | none | Start date filter |
| `to` | `ISO-8601 date` | none | End date filter |

**Response `200` — `BalanceSnapshot[]`:**
```json
[
  {
    "id": 1,
    "date": "2025-01-15",
    "balance": 7500.00,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

---

#### `POST /api/accounts/{id}/snapshot`

- **Auth:** Required

**Request body — `SnapshotRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `balance` | `number` | @NotNull, @DecimalMin("0") |
| `date` | `string` | @NotNull, ISO-8601 date |

**Response `201` — `BalanceSnapshot`.**

**Errors:** 404, 422

---

#### `GET /api/accounts/{id}/transactions`

- **Auth:** Required

**Response `200` — `TransactionDto[]`:**
```json
[
  {
    "id": 1,
    "date": "2025-01-15",
    "description": "Apple Inc. - Buy",
    "amount": -1500.00,
    "type": "buy",
    "category": "stock",
    "nativeCurrency": "EUR"
  }
]
```

---

### 4. Goals — `/api/goals`

#### `GET /api/goals`

- **Auth:** Required

**Response `200` — `GoalProgressResponse[]`:**
```json
[
  {
    "id": 1,
    "name": "Vacation Fund",
    "targetAmount": 3000.00,
    "deadline": "2025-12-31",
    "accounts": [ /* AccountResponse[] */ ],
    "currentTotal": 1200.00,
    "percentComplete": 40.0,
    "monthsLeft": 9,
    "monthlyNeeded": 200.00,
    "avgMonthlyContribution": 150.00,
    "isOnTrack": true,
    "surplus": -50.00
  }
]
```

---

#### `GET /api/goals/{id}`

- **Auth:** Required

**Response `200` — `GoalProgressResponse`** (same shape as above).

**Errors:** 404

---

#### `POST /api/goals`

- **Auth:** Required

**Request body — `GoalRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `name` | `string` | @NotBlank, max 200 |
| `targetAmount` | `number` | @NotNull, @DecimalMin("0.01") |
| `deadline` | `string` | @NotNull, @Future, ISO-8601 date |
| `accountIds` | `number[]` | @NotEmpty, list of account IDs |

**Response `201` — `GoalProgressResponse`.**

**Errors:** 422

---

#### `PUT /api/goals/{id}`

- **Auth:** Required
- **Body:** same `GoalRequest` as POST

**Response `200` — `GoalProgressResponse`.**

**Errors:** 404, 422

---

#### `DELETE /api/goals/{id}`

- **Auth:** Required
- **Body:** none

**Response `204`.**

**Errors:** 404

---

#### `GET /api/goals/{id}/months`

- **Auth:** Required

**Response `200` — `GoalMonthEntryResponse[]`:**
```json
[
  {
    "yearMonth": "2025-01",
    "objective": 200.00,
    "actual": 150.00,
    "override": null,
    "effective": 150.00
  }
]
```

---

#### `PUT /api/goals/{id}/months/{yearMonth}`

- **Auth:** Required
- **Path:** `yearMonth` in format `yyyy-MM`

**Request body — `GoalMonthOverrideRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `amount` | `number` | @NotNull, @DecimalMin("0") |

**Response `200` — `GoalMonthEntryResponse`.**

---

#### `DELETE /api/goals/{id}/months/{yearMonth}`

- **Auth:** Required
- **Path:** `yearMonth` in format `yyyy-MM`

**Response `200` — `GoalMonthEntryResponse`** (with `override: null`).

---

### 5. Bank Sync (Enable Banking) — `/api/sync`

#### `GET /api/sync/institutions`

- **Auth:** Required

**Query params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | `string` | `""` | Search filter |
| `country` | `string` | `"FR"` | ISO country code |

**Response `200` — `InstitutionData[]`:**
```json
[
  {
    "id": "BNP_PARIBAS",
    "name": "BNP Paribas",
    "bic": "BNPAFRPP",
    "logoUrl": "https://...",
    "country": "FR"
  }
]
```

---

#### `POST /api/sync/initiate`

- **Auth:** Required
- **Rate limit:** Throttled

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `institutionId` | `string` | Bank identifier from `/institutions` |
| `institutionName` | `string` | Display name |

**Response `200` — `InitiateResponse`:**
```json
{
  "requisitionId": "uuid",
  "authLink": "https://ob.nordigen.com/psd2/..."
}
```

**Errors:** 429, 502

---

#### `GET /api/sync/complete`

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `code` | `string` | OAuth authorization code |

**Response `200` — `AccountResponse[]`.**

**Errors:** 401 (invalid code), 502

---

#### `GET /api/sync/status`

- **Auth:** Required

**Response `200` — `Requisition[]`:**
```json
[
  {
    "id": 1,
    "requisitionId": "uuid",
    "institutionId": "BNP_PARIBAS",
    "institutionName": "BNP Paribas",
    "status": "LINKED",
    "authLink": null
  }
]
```

`status` values: `CREATED` · `LINKED` · `EXPIRED` · `FAILED`

---

#### `POST /api/sync/{id}/retry`

- **Auth:** Required

**Response `200` — `AccountResponse[]`.**

**Errors:** 404, 502

---

#### `DELETE /api/sync/{id}`

- **Auth:** Required

**Response `204`.**

**Errors:** 404

---

### 6. Trade Republic — `/api/tr`

#### `POST /api/tr/auth/initiate`

- **Auth:** Required
- **Rate limit:** Throttled

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `phoneNumber` | `string` | Phone number |
| `pin` | `string` | Account PIN |

**Response `200` — `AuthInitResponse`:**
```json
{ "processId": "string" }
```

**Errors:** 429, 502

---

#### `POST /api/tr/auth/complete`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `processId` | `string` | From initiate step |
| `tan` | `string` | 2FA code from SMS |

**Response `200` — `AccountResponse[]`.**

**Errors:** 401, 502

---

#### `POST /api/tr/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse[]`.**

**Errors:** 401 (no active session), 502

---

#### `GET /api/tr/status`

- **Auth:** Required

**Response `200` — `SessionStatusResponse`:**
```json
{
  "isActive": true,
  "expiresAt": "2025-03-15T12:00:00Z"
}
```

---

#### `POST /api/tr/import`

- **Auth:** Required
- **Content-Type:** `multipart/form-data`
- **Field:** `file` (CSV)

**Response `200` — `AccountResponse[]`.**

---

#### `DELETE /api/tr/session`

- **Auth:** Required
- **Body:** none

**Response `204`.**

---

### 7. Bourse Direct — `/api/bourse-direct`

The connector is read-only. Authentication persists an encrypted browser
session, then portfolio import continues asynchronously.

#### `POST /api/bourse-direct/auth/initiate`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "login": "client-id", "password": "secret" }
```

**Response `200` — `BourseDirectAuthInitResponse`:**
```json
{ "processId": "uuid", "mfaRequired": true, "mfaType": "OTP" }
```

When `mfaRequired` is false, the encrypted session is already stored and its
first portfolio import is queued.

---

#### `POST /api/bourse-direct/auth/complete`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "processId": "uuid", "code": "123456" }
```

**Response `200` — `BourseDirectSessionStatus`**, normally with
`syncStatus: "QUEUED"`.

---

#### `POST /api/bourse-direct/sync`

- **Auth:** Required
- **Body:** none

**Response `202` — `BourseDirectSessionStatus`.** An already queued or running
job is not duplicated; its current status is returned.

---

#### `GET /api/bourse-direct/status`

- **Auth:** Required

**Response `200` — `BourseDirectSessionStatus`:**
```json
{
  "isActive": true,
  "expiresAt": null,
  "syncStatus": "SUCCESS",
  "lastSyncStartedAt": "2026-07-20T09:59:40Z",
  "lastSyncCompletedAt": "2026-07-20T10:00:00Z",
  "lastSyncError": null
}
```

`syncStatus` is one of `IDLE`, `QUEUED`, `RUNNING`, `SUCCESS`, or `FAILED`.

---

#### `DELETE /api/bourse-direct/session`

- **Auth:** Required

**Response `204`.** Imported accounts and history are retained.

Domain failures use `422` RFC 7807 responses with a stable `code` property:
`INVALID_CREDENTIALS`, `INVALID_OTP`, `AUTH_ATTEMPT_EXPIRED`,
`SESSION_EXPIRED`, `PORTFOLIO_INCOMPLETE`, `UPSTREAM_FORMAT_CHANGED`,
`UPSTREAM_UNAVAILABLE`, `INVALID_DATA`, or `INTERNAL_ERROR`. Authentication
rate limiting returns `429`.

---

### 8. Crypto Wallets — `/api/crypto/wallet`

#### `POST /api/crypto/wallet`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `chain` | `Chain` | `SOLANA` · `ETHEREUM` · `BITCOIN` |
| `address` | `string` | Wallet address |
| `label` | `string` | Display label |

**Response `200` — `AccountResponse`.**

---

#### `POST /api/crypto/wallet/{id}/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse`** (updated with latest balance).

---

#### `GET /api/crypto/wallet`

- **Auth:** Required

**Response `200` — `WalletStatusResponse[]`:**
```json
[
  {
    "id": 1,
    "chain": "ETHEREUM",
    "address": "0x...",
    "label": "My Wallet",
    "lastSyncedAt": "2025-03-15T10:00:00Z"
  }
]
```

---

#### `DELETE /api/crypto/wallet/{id}`

- **Auth:** Required

**Response `204`.**

---

### 9. Crypto Exchanges — `/api/crypto/exchange`

#### `POST /api/crypto/exchange`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `type` | `ExchangeType` | `BINANCE` · `KRAKEN` |
| `apiKey` | `string` | Exchange API key |
| `apiSecret` | `string` | Exchange API secret |

**Response `200` — `AccountResponse`.**

---

#### `POST /api/crypto/exchange/{id}/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse`** (updated with latest holdings).

---

#### `GET /api/crypto/exchange/status`

- **Auth:** Required

**Response `200` — `ExchangeStatusResponse[]`:**
```json
[
  {
    "id": 1,
    "exchangeType": "BINANCE",
    "status": "CONNECTED",
    "lastSyncedAt": "2025-03-15T10:00:00Z"
  }
]
```

---

#### `DELETE /api/crypto/exchange/{id}`

- **Auth:** Required

**Response `204`.**

---

### 10. Prices — `/api/prices`

#### `GET /api/prices`

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `tickers` | `string` | Comma-separated ticker symbols, e.g. `"BTC,ETH,AAPL"` |

**Response `200`:**
```json
{
  "BTC": 45000.00,
  "ETH": 3000.00,
  "AAPL": 180.00
}
```

Prices are in EUR. Results are cached for 15 minutes.

---

### 11. Finary — `/api/finary`

Two import modes: **file-based** (XLSX upload) and **API-based** (direct sync). Both use a two-phase flow: preview then execute with account mappings.

#### `POST /api/finary/preview` (file-based)

- **Auth:** Required
- **Content-Type:** `multipart/form-data`
- **Field:** `file` (XLSX)

**Response `200` — `FinaryPreviewResponse`:**
```json
{
  "accounts": [
    {
      "finaryName": "Compte Courant",
      "finaryInstitution": "BoursoBank",
      "finaryCategory": "checking",
      "suggestedType": "CHECKING",
      "currentBalance": 2500.00,
      "nativeCurrency": "EUR",
      "transactionCount": 42
    }
  ],
  "existingPicsouAccounts": [ /* AccountResponse[] */ ],
  "totalTransactionCount": 128,
  "fileToken": "server-side-token"
}
```

---

#### `POST /api/finary/import` (file-based)

- **Auth:** Required

**Request body — `FinaryImportRequest`:**
```json
{
  "fileToken": "token-from-preview",
  "mappings": [
    {
      "finaryName": "Compte Courant",
      "finaryCategory": "checking",
      "action": "MAP_EXISTING",
      "targetAccountId": 5,
      "newAccount": null
    },
    {
      "finaryName": "PEA",
      "finaryCategory": "stock",
      "action": "CREATE_NEW",
      "targetAccountId": null,
      "newAccount": {
        "name": "PEA Finary",
        "type": "PEA",
        "provider": "Finary",
        "currency": "EUR",
        "color": "#10b981"
      }
    }
  ]
}
```

`action` values: `SKIP` · `MAP_EXISTING` · `CREATE_NEW`

**Response `200` — `FinaryImportResultResponse`:**
```json
{
  "accountsCreated": 1,
  "accountsMapped": 2,
  "accountsSkipped": 0,
  "snapshotsCreated": 3,
  "transactionsImported": 128,
  "importedAccounts": [
    {
      "id": 10,
      "name": "PEA Finary",
      "type": "PEA",
      "currentBalance": 8000.00,
      "color": "#10b981"
    }
  ]
}
```

---

#### `GET /api/finary/configured` (API-based)

- **Auth:** Required

**Response `200`:**
```json
true
```

Returns whether the Finary API credentials (`FINARY_EMAIL`, `FINARY_PASSWORD`) are configured.

---

#### `POST /api/finary/api-sync/preview` (API-based)

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `totp` | `string` | TOTP 2FA code (if enabled) |

**Response `200` — `FinaryPreviewResponse`** (same shape as file-based preview, but with `syncToken` instead of `fileToken`).

---

#### `POST /api/finary/api-sync/execute` (API-based)

- **Auth:** Required

**Request body — `FinaryApiSyncExecuteRequest`:**
```json
{
  "syncToken": "token-from-preview",
  "mappings": [ /* same FinaryAccountMapping[] as file-based */ ]
}
```

**Response `200` — `FinaryImportResultResponse`** (same shape as file-based import).
