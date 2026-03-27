---
name: Crypto integrations
overview: Ajouter un système générique d'exchanges crypto (Binance en premier) et un suivi multi-chain de wallets on-chain, les deux retournant un solde total en EUR via CoinGecko.
todos:
  - id: exchange-port
    content: Créer CryptoExchangePort + record CryptoHolding + enum ExchangeType
    status: completed
  - id: binance-adapter
    content: Implémenter BinanceAdapter (HMAC-SHA256, GET /api/v3/account)
    status: completed
  - id: exchange-entity
    content: Créer entité CryptoExchangeSession + repository + migration SQL
    status: completed
  - id: exchange-service
    content: Créer CryptoExchangeSyncService (add, sync, remove, resyncAll)
    status: completed
  - id: exchange-controller
    content: Créer CryptoExchangeController (/api/crypto/exchange)
    status: completed
  - id: exchange-frontend
    content: Créer CryptoExchangeSyncPage.tsx + API client + route
    status: completed
  - id: wallet-port
    content: Créer WalletPort + record WalletBalance + enum Chain
    status: completed
  - id: solana-adapter
    content: Implémenter SolanaWalletAdapter (JSON-RPC getBalance)
    status: completed
  - id: ethereum-adapter
    content: Implémenter EthereumWalletAdapter (JSON-RPC eth_getBalance)
    status: completed
  - id: bitcoin-adapter
    content: Implémenter BitcoinWalletAdapter (API Blockstream)
    status: completed
  - id: wallet-entity
    content: Créer entité WalletAddress + repository + migration SQL
    status: completed
  - id: wallet-service
    content: Créer WalletSyncService (add, sync, remove, resyncAll)
    status: completed
  - id: wallet-controller
    content: Créer WalletController (/api/crypto/wallet)
    status: completed
  - id: wallet-frontend
    content: Créer WalletSyncPage.tsx + API client + route
    status: completed
  - id: scheduler
    content: Intégrer resync crypto dans SchedulerService
    status: completed
  - id: coingecko-extend
    content: Étendre CoinGeckoPriceProvider si nouveaux tickers nécessaires
    status: completed
isProject: false
---

# Intégration Crypto : Exchanges + Wallets On-chain

## Contexte

Le projet a déjà :

- `AccountType.CRYPTO` dans le modèle
- `CoinGeckoPriceProvider` qui convertit BTC/ETH/SOL/etc. en EUR via l'API gratuite
- Le pattern adapter/port bien établi (ex: `TradeRepublicPort` + `TradeRepublicAdapter`)
- Le modèle `Account` avec `precision(20, 8)` sur le solde (adapté aux crypto)

## Partie 1 : Système générique d'exchanges

### Backend

**Nouveau port** : `CryptoExchangePort.java`

```java
public interface CryptoExchangePort {
    String exchangeName();
    List<CryptoHolding> fetchHoldings(String apiKey, String apiSecret);
    boolean testConnection(String apiKey, String apiSecret);
}

record CryptoHolding(String symbol, BigDecimal quantity) {}
```

**Première implémentation** : `BinanceAdapter.java`

- Appel `GET /api/v3/account` avec signature HMAC-SHA256
- Récupère les balances spot (free + locked) par asset
- Filtre les soldes > 0

**Nouvelle entité** : `CryptoExchangeSession.java`

- Champs : `id`, `exchangeType` (enum: BINANCE, KRAKEN, ...), `apiKey`, `apiSecret` (chiffré), `status`, `lastSyncedAt`
- Table : `crypto_exchange_session`

**Nouveau service** : `CryptoExchangeSyncService.java`

- `addExchange(type, apiKey, apiSecret)` : teste la connexion, persiste la session
- `sync(sessionId)` : récupère les holdings, convertit en EUR via `CoinGeckoPriceProvider`, upsert un `Account` type CRYPTO par exchange
- `removeExchange(sessionId)`
- Le solde total = somme(quantity * prix EUR) pour tous les assets

**Nouveau contrôleur** : `CryptoExchangeController.java` sous `/api/crypto/exchange`

- `POST /` : ajouter un exchange (apiKey, apiSecret, type)
- `POST /{id}/sync` : forcer resync
- `GET /status` : liste des exchanges connectés
- `DELETE /{id}` : supprimer

**Scheduler** : ajouter `cryptoExchangeSyncService.resyncAll()` dans [SchedulerService.java](backend/src/main/java/com/picsou/service/SchedulerService.java)

### Frontend

**Nouvelle page** : `CryptoExchangeSyncPage.tsx` (route `/sync/crypto-exchange`)

- Dropdown pour choisir l'exchange (Binance, ...)
- Champs API Key + API Secret
- Bouton "Connecter" puis affichage du statut
- Pattern identique à [TrSyncPage.tsx](frontend/src/pages/sync/TrSyncPage.tsx)

---

## Partie 2 : Wallets on-chain multi-chain

### Backend

**Nouveau port** : `WalletPort.java`

```java
public interface WalletPort {
    String chain();
    WalletBalance fetchBalance(String address);
}

record WalletBalance(String nativeSymbol, BigDecimal nativeAmount) {}
```

**Implémentations** :

- `SolanaWalletAdapter.java` : JSON-RPC `getBalance` (public endpoint `https://api.mainnet-beta.solana.com`)
- `EthereumWalletAdapter.java` : JSON-RPC `eth_getBalance` (public endpoint Cloudflare/Ankr)
- `BitcoinWalletAdapter.java` : API Blockstream `GET /api/address/<addr>`

Chaque adapter retourne le solde natif (SOL, ETH, BTC). La conversion EUR se fait via `CoinGeckoPriceProvider` déjà existant.

**Nouvelle entité** : `WalletAddress.java`

- Champs : `id`, `chain` (enum: SOLANA, ETHEREUM, BITCOIN), `address`, `label` (optionnel)
- Table : `wallet_address`

**Nouveau service** : `WalletSyncService.java`

- `addWallet(chain, address, label)` : valide le format, persiste
- `sync(walletId)` : récupère le solde natif, convertit en EUR, upsert `Account` type CRYPTO
- `removeWallet(walletId)`
- `resyncAll()` : sync tous les wallets

**Nouveau contrôleur** : `WalletController.java` sous `/api/crypto/wallet`

- `POST /` : ajouter un wallet (chain, address, label)
- `POST /{id}/sync` : forcer resync
- `GET /` : liste des wallets
- `DELETE /{id}` : supprimer

### Frontend

**Nouvelle page** : `WalletSyncPage.tsx` (route `/sync/crypto-wallet`)

- Dropdown chain (Solana, Ethereum, Bitcoin)
- Champ adresse + label optionnel
- Bouton "Ajouter" + liste des wallets avec statut sync
- Pattern similaire à la page TR

---

## Fichiers impactés (existants)

- [router.tsx](frontend/src/router.tsx) : ajout routes `/sync/crypto-exchange` et `/sync/crypto-wallet`
- [SchedulerService.java](backend/src/main/java/com/picsou/service/SchedulerService.java) : ajout resync crypto
- [CoinGeckoPriceProvider.java](backend/src/main/java/com/picsou/adapter/CoinGeckoPriceProvider.java) : potentiellement étendre le mapping `TICKER_TO_ID` si besoin
- Migration SQL : nouvelles tables `crypto_exchange_session` et `wallet_address`
- Navigation frontend : liens vers les nouvelles pages sync

## Sécurité

- Les API secrets Binance doivent être chiffrés en base (AES-256 avec clé dans le `.env`)
- Les API keys Binance doivent être en **lecture seule** (recommandation à afficher dans l'UI)
- Les wallets on-chain n'ont besoin que d'adresses publiques (aucun secret)

