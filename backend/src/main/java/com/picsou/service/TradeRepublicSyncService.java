package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrTokens;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TradeRepublicSyncService {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicSyncService.class);

    private final TradeRepublicPort             trPort;
    private final TradeRepublicSessionRepository sessionRepository;
    private final AccountRepository             accountRepository;
    private final AccountService                accountService;

    public TradeRepublicSyncService(
        TradeRepublicPort trPort,
        TradeRepublicSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountService accountService
    ) {
        this.trPort            = trPort;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.accountService    = accountService;
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    /**
     * Step 1: Sends phone+PIN to TR, triggers SMS. Returns processId.
     * Credentials are used immediately and never stored.
     */
    @Transactional(readOnly = true)
    public AuthInitResponse initiateAuth(String phoneNumber, String pin) {
        String processId = trPort.initiateAuth(phoneNumber, pin);
        return new AuthInitResponse(processId);
    }

    /** Step 2: Exchanges 2FA code for session + refresh tokens, stores them, runs initial sync. */
    public List<AccountResponse> completeAuth(String processId, String tan) {
        TrTokens tokens = trPort.completeAuth(processId, tan);

        sessionRepository.deleteAll();
        TradeRepublicSession session = TradeRepublicSession.builder()
            .sessionToken(tokens.sessionToken())
            .refreshToken(tokens.refreshToken())
            .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS)) // refresh token validity
            .build();
        sessionRepository.save(session);

        log.info("Trade Republic session stored (refresh token: {}), running initial sync",
                 tokens.refreshToken() != null ? "yes" : "no");
        return syncWithToken(tokens.sessionToken(), sessionRepository.findTopByOrderByCreatedAtDesc().orElse(null));
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /** Manual or scheduled sync using the stored session, with auto-refresh. */
    public List<AccountResponse> sync() {
        TradeRepublicSession stored = sessionRepository.findTopByOrderByCreatedAtDesc()
            .orElseThrow(() -> new SyncException("Aucune session Trade Republic. Veuillez vous connecter."));
        return syncWithToken(stored.getSessionToken(), stored);
    }

    private List<AccountResponse> syncWithToken(String sessionToken, TradeRepublicSession stored) {
        try {
            List<TrAccountData> accounts = trPort.fetchAccounts(sessionToken);
            List<AccountResponse> responses = accounts.stream()
                .map(this::upsertAccount)
                .toList();
            log.info("Trade Republic sync complete: {} accounts updated", responses.size());
            return responses;
        } catch (SyncException e) {
            if ("SESSION_EXPIRED".equals(e.getMessage())) {
                // Try to refresh via stored refresh token
                if (stored != null && stored.getRefreshToken() != null) {
                    log.info("TR session expired — attempting refresh with stored refresh token");
                    return refreshAndRetry(stored);
                }
                log.warn("TR session expired — no refresh token available, clearing session");
                sessionRepository.deleteAll();
                throw new SyncException(
                    "Session Trade Republic expirée. Veuillez vous reconnecter depuis la page Trade Republic.");
            }
            throw e;
        }
    }

    private List<AccountResponse> refreshAndRetry(TradeRepublicSession stored) {
        try {
            TrTokens newTokens = trPort.refreshSession(stored.getRefreshToken());
            stored.setSessionToken(newTokens.sessionToken());
            if (newTokens.refreshToken() != null) {
                stored.setRefreshToken(newTokens.refreshToken());
            }
            stored.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
            sessionRepository.save(stored);
            log.info("TR session refreshed — retrying sync");
            return syncWithToken(newTokens.sessionToken(), null); // null = no retry on next expiry
        } catch (SyncException ex) {
            log.warn("TR refresh failed — clearing session");
            sessionRepository.deleteAll();
            throw new SyncException(
                "Session Trade Republic expirée et refresh échoué. Veuillez vous reconnecter.");
        }
    }

    // ─── CSV import (fallback) ─────────────────────────────────────────────────

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
    public List<AccountResponse> importCsv(MultipartFile file) {
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

                responses.add(upsertAccount(new TrAccountData(externalId, name, type, balance)));
            }

        } catch (Exception ex) {
            throw new SyncException("Failed to parse CSV: " + ex.getMessage());
        }

        log.info("TR CSV import complete: {} accounts processed", responses.size());
        return responses;
    }

    // ─── Session status ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SessionStatusResponse getSessionStatus() {
        Optional<TradeRepublicSession> session = sessionRepository.findTopByOrderByCreatedAtDesc();
        if (session.isEmpty()) {
            return new SessionStatusResponse(false, null);
        }
        TradeRepublicSession s = session.get();
        boolean active = s.getExpiresAt() == null || s.getExpiresAt().isAfter(Instant.now());
        return new SessionStatusResponse(active, s.getExpiresAt());
    }

    public void clearSession() {
        sessionRepository.deleteAll();
        log.info("Trade Republic session cleared");
    }

    // ─── Scheduler entry point ─────────────────────────────────────────────────

    /** Called by SchedulerService. No-op if no active session. */
    public void resyncIfSessionActive() {
        Optional<TradeRepublicSession> session = sessionRepository.findTopByOrderByCreatedAtDesc();
        if (session.isEmpty()) return;

        TradeRepublicSession s = session.get();
        if (s.getExpiresAt() != null && !s.getExpiresAt().isAfter(Instant.now())) {
            log.warn("Trade Republic session expired — skipping auto-sync. Re-authenticate via the UI.");
            return;
        }

        try {
            syncWithToken(s.getSessionToken(), s);
        } catch (Exception ex) {
            log.warn("Trade Republic auto-sync failed: {}", ex.getMessage());
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private AccountResponse upsertAccount(TrAccountData data) {
        Optional<Account> existing = accountRepository.findByExternalAccountId(data.externalId());

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balanceEur());
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
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
        return accountService.toResponse(account);
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

    // ─── Response records ──────────────────────────────────────────────────────

    public record AuthInitResponse(String processId) {}

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
