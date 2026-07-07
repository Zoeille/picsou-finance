package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.port.TradeRepublicPort.TrTokens;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Transactional
public class TradeRepublicSyncService {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicSyncService.class);

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tr-sync");
        t.setDaemon(true);
        return t;
    });

    private final TradeRepublicPort             trPort;
    private final TradeRepublicSessionRepository sessionRepository;
    private final AccountRepository             accountRepository;
    private final AccountHoldingRepository      holdingRepository;
    private final FamilyMemberRepository        familyMemberRepository;
    private final AccountService                accountService;
    private final OpenFigiIsinConverter         isinConverter;
    private final CryptoEncryption              encryption;
    private final TransactionTemplate           txTemplate;

    public TradeRepublicSyncService(
        TradeRepublicPort trPort,
        TradeRepublicSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate
    ) {
        this.trPort            = trPort;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService    = accountService;
        this.isinConverter     = isinConverter;
        this.encryption        = encryption;
        this.txTemplate        = txTemplate;
    }

    // --- Auth ---

    /**
     * Step 1: Sends phone+PIN to TR, triggers SMS. Returns processId.
     * Credentials are used immediately and never stored.
     */
    @Transactional(readOnly = true)
    public AuthInitResponse initiateAuth(String phoneNumber, String pin) {
        String processId = trPort.initiateAuth(phoneNumber, pin);
        return new AuthInitResponse(processId);
    }

    /**
     * Step 2: Exchanges 2FA code for session + refresh tokens, stores them.
     * Returns immediately -- sync runs in background.
     */
    public SessionStatusResponse completeAuth(String processId, String tan, Long memberId) {
        TrTokens tokens = trPort.completeAuth(processId, tan);

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        // Delete any existing sessions for this member
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);

        TradeRepublicSession session = TradeRepublicSession.builder()
            .member(member)
            .sessionToken(encryption.encrypt(tokens.sessionToken()))
            .refreshToken(encryption.encrypt(tokens.refreshToken()))
            .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .build();
        sessionRepository.save(session);

        log.info("Trade Republic session stored for member {} (refresh token: {}), firing background sync",
                 memberId, tokens.refreshToken() != null ? "yes" : "no");

        String plainToken = tokens.sessionToken();
        Long sessionId = session.getId();
        CompletableFuture.runAsync(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    TradeRepublicSession savedSession = sessionRepository.findById(sessionId).orElse(null);
                    syncWithToken(plainToken, savedSession, memberId);
                });
                log.info("Trade Republic background sync complete");
            } catch (Exception ex) {
                log.error("Trade Republic background sync failed: {}", ex.getMessage());
            }
        }, syncExecutor);

        return new SessionStatusResponse(true, session.getExpiresAt());
    }

    // --- Sync ---

    /** Manual or scheduled sync using the stored session, with auto-refresh. */
    public List<AccountResponse> sync(Long memberId) {
        TradeRepublicSession stored = sessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No Trade Republic session. Please connect from the Trade Republic page."));
        return syncWithToken(encryption.decrypt(stored.getSessionToken()), stored, memberId);
    }

    private List<AccountResponse> syncWithToken(String sessionToken, TradeRepublicSession stored, Long memberId) {
        try {
            List<TrAccountData> accounts = trPort.fetchAccounts(sessionToken);
            List<AccountResponse> responses = accounts.stream()
                .map(data -> upsertAccount(data, memberId, true))
                .flatMap(Optional::stream)
                .toList();
            log.info("Trade Republic sync complete: {} accounts updated", responses.size());
            return responses;
        } catch (SyncException e) {
            if ("SESSION_EXPIRED".equals(e.getMessage())) {
                // Try to refresh via stored refresh token
                if (stored != null && stored.getRefreshToken() != null) {
                    log.info("TR session expired -- attempting refresh with stored refresh token");
                    return refreshAndRetry(stored, memberId);
                }
                log.warn("TR session expired -- no refresh token available, clearing session");
                sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
                throw new SyncException(
                    "Your Trade Republic session has expired. Please reconnect from the Trade Republic page.");
            }
            throw e;
        }
    }

    private List<AccountResponse> refreshAndRetry(TradeRepublicSession stored, Long memberId) {
        try {
            TrTokens newTokens = trPort.refreshSession(encryption.decrypt(stored.getRefreshToken()));
            stored.setSessionToken(encryption.encrypt(newTokens.sessionToken()));
            if (newTokens.refreshToken() != null) {
                stored.setRefreshToken(encryption.encrypt(newTokens.refreshToken()));
            }
            stored.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
            sessionRepository.save(stored);
            log.info("TR session refreshed -- retrying sync");
            return syncWithToken(newTokens.sessionToken(), null, memberId); // null = no retry on next expiry
        } catch (SyncException ex) {
            log.warn("TR refresh failed -- clearing session");
            sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
            throw new SyncException(
                "Your Trade Republic session has expired and could not be refreshed. Please reconnect.");
        }
    }

    // --- CSV import (fallback) ---

    /**
     * Imports account balances from a CSV file.
     * Expected format (header required):
     * <pre>
     * name,type,balance
     * PEA Trade Republic,PEA,15000.50
     * CTO Trade Republic,COMPTE_TITRES,5000.00
     * Cash TR,CHECKING,250.00
     * </pre>
     * Valid types: PEA, COMPTE_TITRES, CRYPTO, CHECKING, SAVINGS, LEP, OTHER
     */
    public List<AccountResponse> importCsv(MultipartFile file, Long memberId) {
        List<AccountResponse> responses = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header
                if (firstLine) {
                    firstLine = false;
                    if (line.toLowerCase().startsWith("name")) continue;
                }

                String[] parts = line.split(",", 3);
                if (parts.length < 3) {
                    log.warn("TR CSV: skipping malformed line: {}", line);
                    continue;
                }

                String name    = parts[0].trim();
                String typeStr = parts[1].trim().toUpperCase();
                String balStr  = parts[2].trim();

                AccountType type;
                try {
                    type = AccountType.valueOf(typeStr);
                } catch (IllegalArgumentException ex) {
                    log.warn("TR CSV: unknown type '{}' on line '{}', using OTHER", typeStr, line);
                    type = AccountType.OTHER;
                }

                BigDecimal balance;
                try {
                    balance = new BigDecimal(balStr);
                } catch (NumberFormatException ex) {
                    log.warn("TR CSV: invalid balance '{}' on line '{}'", balStr, line);
                    continue;
                }

                // Deduplicate via a stable external ID derived from the name
                String externalId = "tr_csv_" + name.toLowerCase()
                    .replaceAll("[^a-z0-9]", "_")
                    .replaceAll("_+", "_");

                upsertAccount(new TrAccountData(externalId, name, type, balance, List.of()), memberId, false)
                    .ifPresent(responses::add);
            }

        } catch (Exception ex) {
            throw new SyncException("Failed to parse CSV: " + ex.getMessage());
        }

        log.info("TR CSV import complete: {} accounts processed", responses.size());
        return responses;
    }

    // --- Session status ---

    @Transactional(readOnly = true)
    public SessionStatusResponse getSessionStatus(Long memberId) {
        Optional<TradeRepublicSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) {
            return new SessionStatusResponse(false, null);
        }
        TradeRepublicSession s = session.get();
        boolean active = s.getExpiresAt() == null || s.getExpiresAt().isAfter(Instant.now());
        return new SessionStatusResponse(active, s.getExpiresAt());
    }

    public void clearSession(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("Trade Republic session cleared for member {}", memberId);
    }

    // --- Scheduler entry point ---

    /** Called by SchedulerService. No-op if no active session for this member. */
    public void resyncIfSessionActive(Long memberId) {
        Optional<TradeRepublicSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) return;

        TradeRepublicSession s = session.get();
        if (s.getExpiresAt() != null && !s.getExpiresAt().isAfter(Instant.now())) {
            log.warn("Trade Republic session expired for member {} -- skipping auto-sync. Re-authenticate via the UI.", memberId);
            return;
        }

        try {
            syncWithToken(encryption.decrypt(s.getSessionToken()), s, memberId);
        } catch (Exception ex) {
            log.warn("Trade Republic auto-sync failed for member {}: {}", memberId, ex.getMessage());
        }
    }

    // --- Private ---

    private Optional<AccountResponse> upsertAccount(TrAccountData data, Long memberId, boolean replaceHoldings) {
        log.debug("TR upsertAccount: looking for externalId={} memberId={}", data.externalId(), memberId);
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        log.debug("TR upsertAccount: found existing={}", existing.isPresent());

        if (existing.isEmpty() &&
            accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("TR: skipping resurrection of soft-deleted account externalId={} member={}",
                data.externalId(), memberId);
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balanceEur());
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(data.name())
                .type(data.type())
                .provider("Trade Republic")
                .currency("EUR")
                .currentBalance(data.balanceEur())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color(colorFor(data.type()))
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balanceEur(), LocalDate.now());

        if (replaceHoldings) {
            holdingRepository.deleteByAccountId(account.getId());
            holdingRepository.flush();

            if (data.positions().isEmpty()) {
                return Optional.of(accountService.toResponse(account));
            }

            // Deduplicate by ticker: when multiple ISINs convert to the same ticker,
            // aggregate them via VWAP to avoid unique constraint violations and
            // preserve a meaningful weighted average buy-in.
            Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
            for (TrPosition p : data.positions()) {
                var result = isinConverter.resolve(p.isin());
                String ticker = result.ticker();
                String name = result.name();
                deduped.merge(
                    ticker,
                    new HoldingDedup.HoldingAgg(p.quantity(), p.averageBuyIn(), p.currentPrice(), name),
                    HoldingDedup::vwapMerge);
            }
            for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
                HoldingDedup.HoldingAgg agg = entry.getValue();
                holdingRepository.save(AccountHolding.builder()
                    .account(account)
                    .ticker(entry.getKey())
                    .name(agg.name())
                    .quantity(agg.quantity())
                    .averageBuyIn(agg.averageBuyIn())
                    .currentPrice(agg.currentPrice())
                    .lastSyncedAt(Instant.now())
                    .build());
            }
        }

        return Optional.of(accountService.toResponse(account));
    }

    private String colorFor(AccountType type) {
        return switch (type) {
            case PEA           -> "#10b981"; // green
            case COMPTE_TITRES -> "#3b82f6"; // blue
            case CRYPTO        -> "#f59e0b"; // amber
            case SAVINGS       -> "#8b5cf6"; // purple
            default            -> "#6366f1"; // indigo
        };
    }

    // --- Response records ---

    public record AuthInitResponse(String processId) {}

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
