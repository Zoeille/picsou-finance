# Feature: Crypto Tracking

> Last updated: 2026-07-16

## Context

Picsou tracks cryptocurrency holdings from two sources: centralized exchanges (Binance) and on-chain wallets (Bitcoin, Ethereum, Solana). Exchange credentials are encrypted at rest with AES-256-GCM. On-chain wallets query public blockchain RPCs. All crypto balances are converted to EUR via the `PriceService`.

## How it works

### Three subsystems

1. **CryptoExchangeSyncService** -- Manages exchange connections. Stores encrypted API key + encrypted secret in `CryptoExchangeSession`. Both fields are encrypted with AES-256-GCM. Fetches holdings, converts to EUR via `PriceService.refreshPrices()`, and upserts a single account per exchange with per-coin holdings in `AccountHolding`.

2. **WalletSyncService** -- Manages on-chain wallet addresses. Stores chain type + address in `WalletAddress`. Fetches native balance via `WalletPort`, converts to EUR, and upserts an account. Does NOT store a ticker on the account to prevent double price conversion (balance is already in EUR).

3. **PriceService** -- Provides EUR prices for crypto tickers via CoinGecko. See [price-service.md](./price-service.md).

### AES-256-GCM encryption

`CryptoEncryption` handles encryption/decryption of API keys and secrets stored in the database. Both `apiKey` and `apiSecret` are encrypted. It uses `AES/GCM/NoPadding` with a 12-byte IV and 128-bit tag. The IV is prepended to the ciphertext before Base64 encoding. The encryption key is provided via the `CRYPTO_ENCRYPTION_KEY` environment variable (Base64-encoded 256-bit key). The app **refuses to start** if the key is not set. See [encryption-at-rest.md](./encryption-at-rest.md) for full details.

### Binance adapter

`BinanceAdapter` implements `CryptoExchangePort`. It calls the Binance REST API (`GET /api/v3/account`) with HMAC-SHA256 signed requests. Returns a list of `CryptoHolding` records for all assets with non-zero balances (free + locked). The `testConnection()` method validates credentials before saving.

### Bitcoin wallet adapter

`BitcoinWalletAdapter` implements `WalletPort`. It supports three input formats for the address field:

- **Plain address** (`bc1q...`, `1...`, `3...`) -- Single address lookup via Blockstream Esplora API.
- **Extended public key** (`xpub...`, `zpub...`) -- BIP32 HD wallet key derivation. Derives P2WPKH addresses (BIP84) along external (`m/0/*`) and change (`m/1/*`) chains.
- **Output descriptor** (`wpkh([fingerprint/path]xpub.../chain/*)#checksum`) -- Proton Wallet format, normalized to xpub before derivation.

The adapter scans each chain until `GAP_LIMIT` (20) consecutive unused addresses are found (BIP44 standard). `BitcoinKeyUtils` provides BIP32 key derivation, Base58Check decoding, and Bech32 encoding.

### Ethereum wallet adapter

`EthereumWalletAdapter` calls `eth_getBalance` on a public Ethereum RPC (`ethereum-rpc.publicnode.com`). Returns balance converted from wei to ETH (18 decimals).

### Solana wallet adapter

`SolanaWalletAdapter` calls `getBalance` on the Solana mainnet RPC (`api.mainnet-beta.solana.com`). Returns balance converted from lamports to SOL (9 decimals). It also calls `getTokenAccountsByOwner` to pick up known SPL stablecoins (USDC/EURC/USDT).

### JSON-RPC error handling

Both on-chain adapters validate the JSON-RPC envelope through `JsonRpcResponse.requireResult(response, context)` (`adapter/util/`) before reading a balance. A present `error` field, a missing `result`, or an empty response throws `WalletRpcException`, which `WalletSyncService` surfaces as a `422` sync failure. This is deliberate: reading `result` with `path(...)` returns a non-null `MissingNode`, so an error payload would otherwise default to a **silent 0 balance** — indistinguishable from a genuinely empty wallet. A real `0x0` / `value:0` result still returns 0. On Solana, an error on the SPL-token call fails the whole sync rather than silently dropping stablecoin holdings.

`WalletSyncService.sync()` splits its catch: **expected** failures (`WalletRpcException`, `SyncException`) log at `WARN` and become the friendly `422`; **unexpected** ones (NPE, `ClassCastException`, …) log at `ERROR` with the full stacktrace so a real bug can't hide as a transient sync. `WalletRpcException` has no dedicated `@ExceptionHandler` — it stays wrapped in `SyncException`, which keeps the `422` mapping. Two per-item cases inside the Solana adapter are **not** fatal, to avoid one bad field hiding the rest of the wallet: a non-array token `value` and a malformed `uiAmountString` are logged (WARN / ERROR respectively) and that one entry is skipped, while SOL and every parseable token still come through. `resyncAll()` returns a `ResyncSummary(total, succeeded, failed)` so its callers (the daily scheduler and the `trigger_crypto_wallet_sync` MCP tool) can report per-wallet outcomes instead of a blanket "done".

### Key files

- `backend/src/main/java/com/picsou/service/CryptoExchangeSyncService.java` -- Exchange connection management, holding sync
- `backend/src/main/java/com/picsou/service/WalletSyncService.java` -- On-chain wallet management, balance sync
- `backend/src/main/java/com/picsou/config/CryptoEncryption.java` -- AES-256-GCM encrypt/decrypt for API secrets
- `backend/src/main/java/com/picsou/adapter/BinanceAdapter.java` -- Binance REST API with HMAC-SHA256
- `backend/src/main/java/com/picsou/adapter/BitcoinWalletAdapter.java` -- Blockstream Esplora, BIP32 key derivation
- `backend/src/main/java/com/picsou/adapter/EthereumWalletAdapter.java` -- PublicNode ETH RPC
- `backend/src/main/java/com/picsou/adapter/SolanaWalletAdapter.java` -- Solana mainnet RPC
- `backend/src/main/java/com/picsou/adapter/util/JsonRpcResponse.java` -- JSON-RPC envelope validation shared by the wallet adapters
- `backend/src/main/java/com/picsou/exception/WalletRpcException.java` -- thrown on a JSON-RPC error/missing result
- `backend/src/main/java/com/picsou/adapter/util/BitcoinKeyUtils.java` -- BIP32 derivation, Base58Check, Bech32
- `backend/src/main/java/com/picsou/port/CryptoExchangePort.java` -- Exchange port interface
- `backend/src/main/java/com/picsou/port/WalletPort.java` -- Wallet port interface

### Flow

```
Add Exchange:
User submits API key + secret
        |
        v
CryptoExchangeSyncService.addExchange()
        |
        v
BinanceAdapter.testConnection() -- validate credentials
        |
        v
CryptoEncryption.encrypt(key + secret) -- AES-256-GCM
        |
        v
Save CryptoExchangeSession (encryptedKey + encryptedSecret)
        |
        v
BinanceAdapter.fetchHoldings() -- get balances
        |
        v
PriceService.refreshPrices() -- convert to EUR
        |
        v
Upsert Account (type=CRYPTO) + AccountHolding per coin

Add Wallet:
User submits chain + address
        |
        v
WalletSyncService.addWallet()
        |
        v
WalletPort.fetchBalance() -- blockchain RPC call
        |
        v
PriceService.getPriceEur() -- convert native to EUR
        |
        v
Upsert Account (type=CRYPTO, no ticker)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| AES-256-GCM (symmetric) | Simple, no external dependency; sufficient for single-user self-hosted app | HashiCorp Vault, cloud KMS (overkill for self-hosted) |
| No ticker on wallet accounts | Balance is already converted to EUR at sync time; storing a ticker would cause `PriceService.toEur()` to multiply a second time | Store ticker and handle conversion in dashboard (risk of double conversion) |
| BIP32 key derivation in Java | Full control over derivation; no native library dependency | Use a separate Bitcoin library (unnecessary complexity) |
| Blockstream Esplora (public) | Free, no API key needed, reliable | Run own Electrum server (too much for self-hosted) |
| GAP_LIMIT=20 | BIP44 standard; covers the vast majority of HD wallets | Custom gap limit (not configurable, hardcoded) |

## Gotchas / Pitfalls

- **CRYPTO_ENCRYPTION_KEY required**: The app refuses to start without it. Lost key = cannot decrypt existing secrets = must re-enter exchange credentials.
- **No ticker on wallet accounts**: Wallet accounts have `ticker = null` and `provider = "BTC"/"ETH"/"SOL"`. The balance is already in EUR. Do not set a ticker on wallet accounts -- it will cause double price conversion.
- **Bitcoin xpub vs zpub**: Both are supported. `BitcoinKeyUtils.normalizeToXpub()` converts zpub to xpub before derivation. The derivation always produces P2WPKH (native segwit) addresses.
- **Output descriptor parsing**: Descriptors are parsed by extracting the xpub between brackets. The checksum after `#` is ignored. Complex descriptors (multisig, P2SH-wrapped) are not supported.
- **Exchange holdings use PriceService**: Holdings are converted to EUR at sync time using `PriceService.refreshPrices()`. If the price cache is stale (older than 15 min), prices are refreshed on demand.
- **Wallet RPC errors must not read as 0**: When parsing a blockchain JSON-RPC response, always go through `JsonRpcResponse.requireResult(...)` — never `response.path("result")` directly. `path(...)` returns a `MissingNode` for an error payload, which silently becomes a 0 balance (this caused the July 2026 Ethereum outage). `requireResult` uses `get(...)` to reject a missing/error result while still allowing a legitimate `0x0` / `value:0`.
- **Don't re-throw `WalletRpcException` raw from `sync()`**: it has no `@ExceptionHandler`, so a raw re-throw becomes a `500`, not the friendly `422`. Keep it wrapped in `SyncException`. The split catch is only about **log level** — unexpected errors log at ERROR with a stacktrace; the user-facing status/message is unchanged.
- **Per-token Solana failures are logged, not fatal**: a malformed `uiAmountString` or a non-array token `value` is logged and skipped so the SOL balance and other tokens survive. Only an envelope-level RPC `error`/missing-`result` (via `requireResult`) fails the whole sync. Loud (logged) ≠ fatal (thrown) — pick per blast radius.

## Tests

- `CryptoEncryptionTest` -- unit tests for encrypt/decrypt roundtrip
- `BitcoinKeyUtilsTest` -- unit tests for BIP32 derivation, address generation
- `CryptoExchangeSyncServiceTest` -- unit tests for exchange management
- `WalletSyncServiceTest` -- unit tests for wallet sync
- `JsonRpcResponseTest` -- envelope validation: valid/zero/empty-array results returned; null/error/missing/explicit-null throw
- `WalletSyncServiceTest` -- RPC error wrapped as `SyncException` (wallet not marked synced), empty balances throw, `resyncAll` summary reports failed chains
- `EthereumWalletAdapterTest` -- valid/zero/error/missing-result parsing, null body, malformed hex
- `SolanaWalletAdapterTest` -- SOL + SPL parsing, unknown-mint drop, RPC-error fails sync, non-array/malformed token skipped (non-fatal)

## Links

- Related ADR: [AES-256-GCM for crypto secrets](../decisions/2026-03-01-aes-gcm-crypto-secrets.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Encryption at rest](./encryption-at-rest.md)
- Related feature: [Price service](./price-service.md)
