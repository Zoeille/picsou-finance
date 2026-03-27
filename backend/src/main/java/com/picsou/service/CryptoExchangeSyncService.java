package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.CryptoExchangePort;
import com.picsou.port.CryptoExchangePort.CryptoHolding;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class CryptoExchangeSyncService {

    private static final Logger log = LoggerFactory.getLogger(CryptoExchangeSyncService.class);

    private final List<CryptoExchangePort> exchangeAdapters;
    private final CryptoExchangeSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final PriceService priceService;
    private final CryptoEncryption encryption;

    public CryptoExchangeSyncService(
        List<CryptoExchangePort> exchangeAdapters,
        CryptoExchangeSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountService accountService,
        PriceService priceService,
        CryptoEncryption encryption
    ) {
        this.exchangeAdapters = exchangeAdapters;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.priceService = priceService;
        this.encryption = encryption;
    }

    public AccountResponse addExchange(ExchangeType type, String apiKey, String apiSecret) {
        CryptoExchangePort adapter = findAdapter(type);

        if (!adapter.testConnection(apiKey, apiSecret)) {
            throw new SyncException("Connexion échouée. Vérifiez vos clés API " + type + ".");
        }

        Optional<CryptoExchangeSession> existing = sessionRepository.findByExchangeType(type);
        CryptoExchangeSession session;
        if (existing.isPresent()) {
            session = existing.get();
            session.setApiKey(apiKey);
            session.setApiSecret(encryption.encrypt(apiSecret));
            session.setStatus("CONNECTED");
        } else {
            session = CryptoExchangeSession.builder()
                .exchangeType(type)
                .apiKey(apiKey)
                .apiSecret(encryption.encrypt(apiSecret))
                .status("CONNECTED")
                .build();
        }
        sessionRepository.save(session);

        return sync(session.getId());
    }

    public AccountResponse sync(Long sessionId) {
        CryptoExchangeSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found: " + sessionId));

        CryptoExchangePort adapter = findAdapter(session.getExchangeType());
        String decryptedSecret = encryption.decrypt(session.getApiSecret());

        try {
            List<CryptoHolding> holdings = adapter.fetchHoldings(session.getApiKey(), decryptedSecret);

            Set<String> tickers = holdings.stream()
                .map(CryptoHolding::symbol)
                .collect(Collectors.toSet());
            Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

            BigDecimal totalEur = BigDecimal.ZERO;
            for (CryptoHolding holding : holdings) {
                BigDecimal price = prices.get(holding.symbol().toUpperCase());
                if (price != null) {
                    totalEur = totalEur.add(
                        holding.quantity().multiply(price).setScale(2, RoundingMode.HALF_UP));
                } else {
                    log.warn("No EUR price for {} — skipping in total", holding.symbol());
                }
            }

            session.setStatus("CONNECTED");
            session.setLastSyncedAt(Instant.now());
            sessionRepository.save(session);

            String externalId = "crypto_exchange_" + session.getExchangeType().name().toLowerCase();
            return upsertAccount(externalId, session.getExchangeType().name(), totalEur);

        } catch (Exception ex) {
            session.setStatus("ERROR");
            sessionRepository.save(session);
            throw new SyncException("Sync " + session.getExchangeType() + " échoué : " + ex.getMessage());
        }
    }

    public void removeExchange(Long sessionId) {
        CryptoExchangeSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found: " + sessionId));

        String externalId = "crypto_exchange_" + session.getExchangeType().name().toLowerCase();
        accountRepository.findByExternalAccountId(externalId).ifPresent(accountRepository::delete);
        sessionRepository.delete(session);
        log.info("Removed exchange session {} and associated account", sessionId);
    }

    public void resyncAll() {
        List<CryptoExchangeSession> sessions = sessionRepository.findAllByOrderByCreatedAtAsc();
        for (CryptoExchangeSession session : sessions) {
            try {
                sync(session.getId());
            } catch (Exception ex) {
                log.warn("Crypto exchange resync failed for {}: {}", session.getExchangeType(), ex.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ExchangeStatusResponse> getStatus() {
        return sessionRepository.findAllByOrderByCreatedAtAsc().stream()
            .map(s -> new ExchangeStatusResponse(
                s.getId(), s.getExchangeType(), s.getStatus(), s.getLastSyncedAt()))
            .toList();
    }

    private CryptoExchangePort findAdapter(ExchangeType type) {
        return exchangeAdapters.stream()
            .filter(a -> a.exchangeName().equalsIgnoreCase(type.name()))
            .findFirst()
            .orElseThrow(() -> new SyncException("Aucun adapteur trouvé pour l'exchange : " + type));
    }

    private AccountResponse upsertAccount(String externalId, String exchangeName, BigDecimal balanceEur) {
        Optional<Account> existing = accountRepository.findByExternalAccountId(externalId);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(balanceEur);
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
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

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, balanceEur, LocalDate.now());
        return accountService.toResponse(account);
    }

    public record ExchangeStatusResponse(
        Long id, ExchangeType exchangeType, String status, java.time.Instant lastSyncedAt) {}
}
