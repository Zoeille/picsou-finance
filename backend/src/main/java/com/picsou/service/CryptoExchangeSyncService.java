package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.ExchangePositionResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.CryptoExchangePort;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangePositionRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@Transactional
public class CryptoExchangeSyncService {

    private static final Logger log = LoggerFactory.getLogger(CryptoExchangeSyncService.class);

    private final List<CryptoExchangePort> exchangeAdapters;
    private final CryptoExchangeSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final PriceService priceService;
    private final CryptoEncryption encryption;
    private final CryptoExchangeStatusWriter statusWriter;
    private final CryptoExchangePositionRepository positionRepository;

    public CryptoExchangeSyncService(
        List<CryptoExchangePort> exchangeAdapters,
        CryptoExchangeSessionRepository sessionRepository,
        AccountRepository accountRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        PriceService priceService,
        CryptoEncryption encryption,
        CryptoExchangeStatusWriter statusWriter,
        CryptoExchangePositionRepository positionRepository
    ) {
        this.exchangeAdapters = exchangeAdapters;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.priceService = priceService;
        this.encryption = encryption;
        this.statusWriter = statusWriter;
        this.positionRepository = positionRepository;
    }

    public AccountResponse addExchange(ExchangeType type, String apiKey, String apiSecret, Long memberId) {
        if (type == null) {
            throw new IllegalArgumentException("An exchange type is required.");
        }
        CryptoExchangePort adapter = findAdapter(type);

        // Validate the credential shape the adapter actually needs, before spending a network
        // round-trip on it. Blank normalises to null: the shared form always sends a string, so a
        // single-key exchange arrives with "" rather than an absent field.
        String key = trimToNull(apiKey);
        String secret = trimToNull(apiSecret);
        if (key == null) {
            throw new IllegalArgumentException("An API key is required.");
        }
        if (adapter.requiresApiSecret() && secret == null) {
            throw new IllegalArgumentException(type + " requires an API secret as well as an API key.");
        }
        if (!adapter.requiresApiSecret() && secret != null) {
            throw new IllegalArgumentException(
                type + " connects with a single read-only API key and takes no API secret.");
        }
        if (!adapter.testConnection(key, secret)) {
            throw new SyncException("Could not connect to " + type + ". Please check your API keys.");
        }

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        Optional<CryptoExchangeSession> existing = sessionRepository.findByExchangeTypeAndMemberId(type, memberId);
        CryptoExchangeSession session;
        if (existing.isPresent()) {
            session = existing.get();
            session.setApiKey(encryption.encrypt(key));
            session.setApiSecret(encryption.encrypt(secret));
            session.setStatus("CONNECTED");
        } else {
            session = CryptoExchangeSession.builder()
                .member(member)
                .exchangeType(type)
                .apiKey(encryption.encrypt(key))
                .apiSecret(encryption.encrypt(secret))
                .status("CONNECTED")
                .build();
        }
        sessionRepository.save(session);

        return sync(session.getId(), memberId);
    }

    public AccountResponse sync(Long sessionId, Long memberId) {
        CryptoExchangeSession session = sessionRepository.findByIdAndMemberId(sessionId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found"));

        CryptoExchangePort adapter = findAdapter(session.getExchangeType());
        String decryptedKey = encryption.decrypt(session.getApiKey());
        String decryptedSecret = encryption.decrypt(session.getApiSecret());

        try {
            List<ExchangePosition> positions = adapter.fetchPositions(decryptedKey, decryptedSecret);

            // The account balance and its holdings are per asset, whatever product it sits under;
            // the per-product split is kept separately, for display only.
            Map<String, BigDecimal> quantities = new TreeMap<>();
            for (ExchangePosition position : positions) {
                quantities.merge(position.symbol().toUpperCase(Locale.ROOT), position.quantity(),
                    BigDecimal::add);
            }

            // Crypto-only: an exchange holding must never fall through to Yahoo Finance and be
            // valued as the equity trading under the same symbol. Quotes rather than raw prices:
            // whatever the provider cannot deliver right now is valued from the last price we
            // recorded for it, because this total is not only displayed — it is written into the
            // account's daily BalanceSnapshot, where a hole becomes a permanent dip in the
            // net-worth history that no later sync goes back to fix.
            Map<String, PriceService.Quote> quotes = priceService.refreshCryptoQuotes(quantities.keySet());
            Map<String, BigDecimal> prices = new TreeMap<>();
            quotes.forEach((ticker, quote) -> prices.put(ticker, quote.price()));

            BigDecimal totalEur = BigDecimal.ZERO;
            boolean anyPriced = false;
            for (var holding : quantities.entrySet()) {
                BigDecimal price = prices.get(holding.getKey());
                if (price != null) {
                    anyPriced = true;
                    totalEur = totalEur.add(
                        holding.getValue().multiply(price).setScale(2, RoundingMode.HALF_UP));
                } else {
                    log.warn("No EUR price for {} -- skipping in total", holding.getKey());
                }
            }

            boolean nothingPriced = !quantities.isEmpty() && !anyPriced;
            String externalId = externalIdOf(session.getExchangeType());

            // Nothing could be valued, not even from a recorded price. What that costs depends
            // entirely on whether there is already an account to overwrite.
            //
            // There is: refuse. Writing would replace a correct balance with a zero and stamp a
            // zero snapshot over it — which is what this instance actually did on 2026-08-01,
            // during a CoinGecko rate-limit, to a 448 EUR account. Past days are never revisited.
            // Failing leaves the previous figures standing and puts the session in ERROR where
            // the user can see it. WalletSyncService applies the same rule on-chain.
            //
            // There is not: proceed. This is the first sync, so there is nothing to protect, and
            // refusing here would roll the whole @Transactional addExchange back — including the
            // session row it had just saved. The user would be told to "try again later" for a
            // failure that, if the coins are simply unmapped, never resolves. The snapshot is
            // still withheld below, so no zero reaches the history either way.
            boolean accountExists = accountRepository
                .findByExternalAccountIdAndMemberId(externalId, memberId).isPresent();
            if (nothingPriced && accountExists) {
                throw new SyncException("No EUR price available for any asset held on this "
                    + "exchange -- refusing to record a zero balance for "
                    + session.getExchangeType());
            }
            if (nothingPriced) {
                log.warn("No EUR price for any asset on the first {} sync -- creating the account "
                    + "unvalued, and recording no snapshot for today", session.getExchangeType());
            }

            session.setStatus("CONNECTED");
            session.setLastSyncedAt(Instant.now());
            sessionRepository.save(session);

            // Resolve or create the account (without snapshot yet). If the account
            // was soft-deleted by the user, skip the rest of this sync — the user
            // explicitly removed it and we won't resurrect.
            Account account = resolveAccount(externalId, session.getExchangeType().name(), totalEur, memberId)
                .orElse(null);
            if (account == null) {
                throw new SyncException(
                    "This account's history was deleted. Remove the " + session.getExchangeType().name() +
                    " session in Settings → Crypto to stop syncing it."
                );
            }

            // Persist individual holdings before snapshot (so calculateInvestedAmount finds them)
            for (var holding : quantities.entrySet()) {
                BigDecimal price = prices.get(holding.getKey());
                if (price != null) {
                    accountService.upsertHolding(account.getId(), memberId, holding.getKey(),
                        holding.getKey(), holding.getValue(), price);
                }
            }

            replacePositions(account, positions);

            // Now create snapshot with correct invested amount from holdings — unless nothing
            // could be valued, in which case today's point would be a zero that nothing revisits.
            if (!nothingPriced) {
                accountService.upsertSnapshot(account, totalEur, LocalDate.now());
            }

            return accountService.toResponse(account);

        } catch (Exception ex) {
            // Through the status writer, not a plain save: this method is @Transactional and
            // rethrows, so a save here would be rolled back on the manual path and the session
            // would keep reading CONNECTED. See CryptoExchangeStatusWriter.
            statusWriter.markError(session.getId());
            log.warn("Crypto exchange sync failed for {}: {}", session.getExchangeType(), ex.getMessage());
            throw new SyncException("Could not sync your " + session.getExchangeType() + " account. Please try again later.");
        }
    }

    public void removeExchange(Long sessionId, Long memberId) {
        CryptoExchangeSession session = sessionRepository.findByIdAndMemberId(sessionId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found"));

        String externalId = externalIdOf(session.getExchangeType());
        accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId)
            .ifPresent(accountRepository::delete);
        sessionRepository.delete(session);
        log.info("Removed exchange session {} and associated account", sessionId);
    }

    public void resyncAll(Long memberId) {
        List<CryptoExchangeSession> sessions = sessionRepository.findAllByMemberId(memberId);
        for (CryptoExchangeSession session : sessions) {
            try {
                sync(session.getId(), memberId);
            } catch (Exception ex) {
                log.warn("Crypto exchange resync failed for {}: {}", session.getExchangeType(), ex.getMessage());
            }
        }
    }

    /**
     * The account's per-product breakdown, valued at live crypto prices.
     *
     * <p>Empty for every account that is not a crypto exchange one, and for exchanges whose
     * adapter reports a single product — the caller falls back to the flat holdings table then.
     */
    @Transactional(readOnly = true)
    public List<ExchangePositionResponse> getPositions(Long accountId, Long memberId) {
        accountService.getOrThrow(accountId, memberId); // member-scoped ownership check

        // Cost basis is tracked per asset on AccountHolding, not per product — Meria reports no
        // per-contract acquisition price. Each line therefore reuses the asset's unit cost and
        // multiplies by its own quantity, so the lines still sum to the holding's cost and P&L.
        Map<String, BigDecimal> unitCost = accountService.getHoldings(accountId, memberId).stream()
            .filter(holding -> holding.averageBuyIn() != null)
            .collect(Collectors.toMap(HoldingResponse::ticker, HoldingResponse::averageBuyIn,
                (a, b) -> a));

        List<CryptoExchangePosition> positions =
            positionRepository.findByAccountIdOrderByProductAscTickerAsc(accountId);

        // One resolution for the whole page. Per-position lookups meant an HTTP request per line
        // on every render, which is exactly the traffic that gets an instance rate-limited — and
        // then leaves it with nothing to display. Crypto-only, for the same reason the sync uses
        // it: a ticker CoinGecko doesn't map must read as "no price", never as the same-named
        // share price.
        Map<String, PriceService.Quote> quotes = priceService.getCryptoQuotes(positions.stream()
            .map(CryptoExchangePosition::getTicker)
            .collect(Collectors.toSet()));

        return positions.stream()
            .map(position -> {
                PriceService.Quote quote = quotes.get(position.getTicker());
                BigDecimal price = quote == null ? null : quote.price();
                BigDecimal quantity = position.getQuantity();
                BigDecimal averageBuyIn = unitCost.get(position.getTicker());

                BigDecimal value = price == null ? null : price.multiply(quantity);
                BigDecimal costBasis = averageBuyIn == null ? null : averageBuyIn.multiply(quantity);
                BigDecimal pnl = value != null && costBasis != null ? value.subtract(costBasis) : null;
                // abs(): the denominator is a magnitude, so the percentage keeps the sign of the
                // P&L itself — same rule as AccountService.toHoldingResponse.
                BigDecimal pnlPercent = pnl != null && costBasis.signum() != 0
                    ? pnl.divide(costBasis.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : null;

                return new ExchangePositionResponse(
                    position.getProduct().name(),
                    position.getTicker(),
                    quantity,
                    position.getPrincipal(),
                    position.getInterest(),
                    averageBuyIn,
                    price,
                    value,
                    costBasis,
                    pnl,
                    pnlPercent,
                    quote == null ? null : quote.asOf(),
                    quote != null && !quote.live()
                );
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ExchangeStatusResponse> getStatus(Long memberId) {
        return sessionRepository.findAllByMemberId(memberId).stream()
            .map(s -> new ExchangeStatusResponse(
                s.getId(), s.getExchangeType(), s.getStatus(), s.getLastSyncedAt()))
            .toList();
    }

    /**
     * Rewrites the account's per-product breakdown from the positions just fetched.
     *
     * <p>Delete-then-insert rather than an upsert-and-prune: these rows are derived display data
     * with no cost basis to preserve, so the simple form has no downside — and it cannot leave a
     * product line behind after the user unstakes everything. Runs in the caller's transaction, so
     * a failure later in the sync rolls the whole rewrite back with it.
     *
     * <p>The delete must be the bulk query, not a derived {@code deleteBy…}: see
     * {@link CryptoExchangePositionRepository#deleteAllForAccount} for why re-inserting the same
     * key in one transaction otherwise trips the unique constraint.
     */
    private void replacePositions(Account account, List<ExchangePosition> positions) {
        positionRepository.deleteAllForAccount(account.getId());
        Instant now = Instant.now();
        positionRepository.saveAll(positions.stream()
            .map(position -> CryptoExchangePosition.builder()
                .account(account)
                .product(position.product())
                .ticker(position.symbol().toUpperCase(Locale.ROOT))
                .quantity(position.quantity())
                .principal(position.principal())
                .interest(position.interest())
                .lastSyncedAt(now)
                .build())
            .toList());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The account key derived from an exchange type. {@code Locale.ROOT} is mandatory: BINANCE and
     * MERIA both contain an {@code I}, which a Turkish default locale lowercases to a dotless
     * {@code ı} — splitting the account from its snapshot history. Same hazard already documented
     * for wallets in {@code docs/features/crypto-tracking.md}.
     */
    private static String externalIdOf(ExchangeType type) {
        return "crypto_exchange_" + type.name().toLowerCase(Locale.ROOT);
    }

    private CryptoExchangePort findAdapter(ExchangeType type) {
        return exchangeAdapters.stream()
            .filter(a -> a.exchangeName().equalsIgnoreCase(type.name()))
            .findFirst()
            .orElseThrow(() -> new SyncException("This exchange isn't supported yet."));
    }

    private Optional<Account> resolveAccount(String externalId, String exchangeName, BigDecimal balanceEur, Long memberId) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);

        if (existing.isEmpty() &&
            accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
            log.info("Crypto exchange: account externalId={} was soft-deleted, skipping sync", externalId);
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(balanceEur);
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(exchangeName + " Crypto")
                .type(AccountType.CRYPTO)
                .provider(exchangeName)
                .currency("EUR")
                .currentBalance(balanceEur)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#f59e0b")
                .build();
        }

        return Optional.of(accountRepository.save(account));
    }

    public record ExchangeStatusResponse(
        Long id, ExchangeType exchangeType, String status, java.time.Instant lastSyncedAt) {}
}
