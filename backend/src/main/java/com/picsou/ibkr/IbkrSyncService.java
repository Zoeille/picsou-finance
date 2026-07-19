package com.picsou.ibkr;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.IbkrConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.IbkrConnection;
import com.picsou.port.IbkrFlexPort;
import com.picsou.port.IbkrFlexPort.IbkrAccountData;
import com.picsou.port.IbkrFlexPort.IbkrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.service.AccountService;
import com.picsou.service.HoldingDedup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates Interactive Brokers sync: stores the encrypted Flex credentials,
 * pulls Open Positions once a day and maps them onto Picsou accounts + holdings.
 *
 * <p>Mirrors {@code TradeRepublicSyncService}'s broker→holdings pattern (delete +
 * recompute, VWAP de-dup, ISIN→ticker via {@link OpenFigiIsinConverter}). The one
 * IBKR-specific twist is currency: IBKR reports cost basis in each security's native
 * currency, so {@code averageBuyIn} is converted to the account's base currency via
 * {@code fxRateToBase}. Net worth itself never depends on that conversion — it is
 * recomputed live in EUR from tickers by {@code AccountService.liveBalanceEur}
 * (Yahoo/CoinGecko, FX-converted), exactly like every other holdings account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class IbkrSyncService {

    private final IbkrFlexPort ibkrFlexPort;
    private final IbkrConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final CryptoEncryption encryption;

    // ---------------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------------

    /** Stores (or replaces) the encrypted Flex token + query id for a member. */
    public void connect(String token, String queryId, Long memberId) {
        if (token == null || token.isBlank() || queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("Both the Flex token and the query id are required.");
        }
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        IbkrConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseGet(() -> IbkrConnection.builder().member(member).build());
        connection.setToken(encryption.encrypt(token.trim()));
        connection.setQueryId(encryption.encrypt(queryId.trim()));
        connection.setStatus("CONNECTED");
        connectionRepository.save(connection);
        log.info("IBKR Flex credentials stored for member {}", memberId);
    }

    @Transactional(readOnly = true)
    public IbkrConnectionStatusResponse getConnectionStatus(Long memberId) {
        Optional<IbkrConnection> connection = connectionRepository.findByMemberId(memberId);
        if (connection.isEmpty()) {
            return new IbkrConnectionStatusResponse(false, null, null, null, null);
        }
        IbkrConnection c = connection.get();
        String maskedToken = maskToken(encryption.decrypt(c.getToken()));
        return new IbkrConnectionStatusResponse(true, c.getId(), c.getStatus(), c.getLastSyncedAt(), maskedToken);
    }

    public void deleteConnection(Long memberId) {
        connectionRepository.findByMemberId(memberId).ifPresent(connectionRepository::delete);
        log.info("IBKR connection cleared for member {}", memberId);
    }

    // ---------------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------------

    /** Manual or scheduled sync using the stored connection. */
    public List<AccountResponse> sync(Long memberId) {
        IbkrConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException(
                "No Interactive Brokers connection. Please connect from the Interactive Brokers page."));
        return syncWithConnection(connection, memberId);
    }

    /** Scheduler entry point — no-op if the member has no connection. */
    public void resyncIfConnected(Long memberId) {
        Optional<IbkrConnection> connection = connectionRepository.findByMemberId(memberId);
        if (connection.isEmpty()) {
            return;
        }
        try {
            syncWithConnection(connection.get(), memberId);
        } catch (Exception ex) {
            log.warn("IBKR auto-sync failed for member {}: {}", memberId, ex.getMessage());
        }
    }

    private List<AccountResponse> syncWithConnection(IbkrConnection connection, Long memberId) {
        String token = encryption.decrypt(connection.getToken());
        String queryId = encryption.decrypt(connection.getQueryId());

        List<IbkrAccountData> accounts;
        try {
            accounts = ibkrFlexPort.fetchOpenPositions(token, queryId);
        } catch (RuntimeException ex) {
            connection.setStatus("ERROR");
            connectionRepository.save(connection);
            throw ex;
        }

        List<AccountResponse> responses = accounts.stream()
            .map(data -> upsertAccount(data, memberId))
            .flatMap(Optional::stream)
            .toList();

        connection.setStatus("CONNECTED");
        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);

        log.info("IBKR sync complete for member {}: {} account(s) updated", memberId, responses.size());
        return responses;
    }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private Optional<AccountResponse> upsertAccount(IbkrAccountData data, Long memberId) {
        String externalId = "ibkr_" + data.accountId();

        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
            log.info("IBKR: skipping resurrection of soft-deleted account {} for member {}", externalId, memberId);
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name("IBKR " + data.accountId())
                .type(AccountType.COMPTE_TITRES)
                .provider("Interactive Brokers")
                .currency("EUR")
                .currentBalance(BigDecimal.ZERO)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#d81222") // IBKR red
                .build();
        }
        account = accountRepository.save(account);

        // Replace holdings wholesale — the statement is the full current picture.
        holdingRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();

        // De-dup by resolved ticker (VWAP) exactly like Trade Republic: several IBKR
        // positions (or lot rows across accounts) can map to one ticker.
        Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
        for (IbkrPosition p : data.positions()) {
            if (!isReportable(p)) {
                continue;
            }
            TickerResult resolved = resolveTicker(p);
            if (resolved.ticker() == null || resolved.ticker().isBlank()) {
                continue;
            }
            deduped.merge(
                resolved.ticker(),
                new HoldingDedup.HoldingAgg(p.position(), eurCostBasis(p), eurMark(p), resolved.name()),
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
        holdingRepository.flush();

        // Net-worth-critical figures use the trusted live-EUR path (Yahoo/CoinGecko,
        // FX-converted), never IBKR's base currency — so net worth is right even if the
        // user's IBKR base currency is not EUR.
        BigDecimal liveEur = accountService.liveBalanceEur(account);
        account.setCurrentBalance(liveEur);
        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, liveEur, LocalDate.now());

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Whether a position should become a holding: non-zero quantity, summary-level
     * (not a per-lot duplicate) and not a cash line. A ticker source (ISIN or symbol)
     * is required so we never persist a nameless holding.
     */
    private boolean isReportable(IbkrPosition p) {
        if (p.position() == null || p.position().signum() == 0) {
            return false;
        }
        // Flex can emit both a SUMMARY row and per-tax-lot ("LOT") rows when lots are
        // enabled on the query. Keep summary/absent, drop LOT, to avoid double counting.
        if (p.levelOfDetail() != null && "LOT".equalsIgnoreCase(p.levelOfDetail())) {
            return false;
        }
        if (p.assetCategory() != null && "CASH".equalsIgnoreCase(p.assetCategory())) {
            return false;
        }
        return (p.isin() != null && !p.isin().isBlank()) || (p.symbol() != null && !p.symbol().isBlank());
    }

    /**
     * Resolves an IBKR position to a price-able ticker + display name. Prefers the
     * ISIN (via OpenFIGI) so the holding prices through Yahoo/CoinGecko; falls back to
     * the IBKR symbol + description when there is no usable ISIN (e.g. some derivatives).
     */
    private TickerResult resolveTicker(IbkrPosition p) {
        if (p.isin() != null && OpenFigiIsinConverter.isIsin(p.isin())) {
            TickerResult result = isinConverter.resolve(p.isin());
            // OpenFIGI may return the ISIN unchanged (miss) and a null name — fall back
            // to the IBKR symbol/description, which at least price-resolves for US tickers.
            if (result.ticker() != null && !OpenFigiIsinConverter.isIsin(result.ticker())) {
                return result;
            }
            String ticker = p.symbol() != null ? p.symbol() : result.ticker();
            String name = result.name() != null ? result.name() : p.description();
            return new TickerResult(ticker, name);
        }
        return new TickerResult(p.symbol(), p.description());
    }

    /** Cost basis per unit in the account base currency (≈EUR). Null when unknown. */
    private BigDecimal eurCostBasis(IbkrPosition p) {
        if (p.costBasisPrice() == null) {
            return null;
        }
        BigDecimal rate = p.fxRateToBase() != null ? p.fxRateToBase() : BigDecimal.ONE;
        return p.costBasisPrice().multiply(rate);
    }

    /**
     * Mark price per unit in the account base currency (≈EUR). Informational only:
     * the stored {@code currentPrice} is never trusted for EUR valuation — the
     * dashboard recomputes live from the ticker (see {@code AccountService}).
     */
    private BigDecimal eurMark(IbkrPosition p) {
        if (p.markPrice() == null) {
            return null;
        }
        BigDecimal rate = p.fxRateToBase() != null ? p.fxRateToBase() : BigDecimal.ONE;
        return p.markPrice().multiply(rate);
    }

    /** Shows only the last 4 characters of the token, e.g. "••••••3F29". */
    private String maskToken(String token) {
        if (token == null || token.length() <= 4) {
            return "••••";
        }
        return "••••••" + token.substring(token.length() - 4);
    }
}
