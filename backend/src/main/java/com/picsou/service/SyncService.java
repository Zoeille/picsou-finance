package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final BankConnectorPort bankConnector;
    private final AccountRepository accountRepository;
    private final RequisitionRepository requisitionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;

    public SyncService(
        BankConnectorPort bankConnector,
        AccountRepository accountRepository,
        RequisitionRepository requisitionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService
    ) {
        this.bankConnector = bankConnector;
        this.accountRepository = accountRepository;
        this.requisitionRepository = requisitionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
    }

    /** Step 1: Initiate Enable Banking bank connection for a given institution. */
    public InitiateResponse initiateConnection(String institutionId, String institutionName, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(institutionId);

        Requisition requisition = Requisition.builder()
            .member(member)
            .requisitionId(result.requisitionId())
            .institutionId(institutionId)
            .institutionName(institutionName)
            .logoUrl(resolveLogoUrl(institutionId, institutionName))
            .status(RequisitionStatus.CREATED)
            .authLink(result.authLink())
            .build();

        requisitionRepository.save(requisition);

        return new InitiateResponse(result.requisitionId(), result.authLink());
    }

    /** Step 2: Complete Enable Banking flow -- exchange OAuth code, fetch balances, upsert accounts. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> completeConnection(String oauthCode, Long memberId) {
        // Find the pending requisition for this member
        Requisition requisition = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No pending bank connection found. Please initiate a new connection."));

        String sessionId;
        try {
            sessionId = bankConnector.exchangeCode(oauthCode);
        } catch (SyncException ex) {
            // Code already used -> find existing linked session and just refresh balances
            if (ex.getMessage().contains("ALREADY_AUTHORIZED")) {
                log.info("Code already used, refreshing latest linked session");
                return resyncLatest(memberId);
            }
            requisition.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(requisition);
            throw ex;
        }

        // Store session_id so the scheduler can re-sync later
        requisition.setRequisitionId(sessionId);

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(sessionId);
        } catch (SyncException ex) {
            requisition.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(requisition);
            throw ex;
        }

        FamilyMember member = requisition.getMember();

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, requisition, member))
            .flatMap(Optional::stream)
            .toList();

        if (markRetryableIfEmpty(requisition, accountDataList, "completion")) {
            return responses;
        }

        requisition.setStatus(RequisitionStatus.LINKED);
        requisition.setLastSyncedAt(Instant.now());
        requisitionRepository.save(requisition);

        log.info("Completed Enable Banking sync for {}: {} accounts linked", requisition.getInstitutionName(), responses.size());
        return responses;
    }

    /** Search available institutions. */
    @Transactional(readOnly = true)
    public List<BankConnectorPort.InstitutionData> searchInstitutions(String query, String country) {
        return bankConnector.searchInstitutions(query, country);
    }

    /** Get all requisitions for a member ordered by date. */
    @Transactional(readOnly = true)
    public List<Requisition> getAllRequisitions(Long memberId) {
        return requisitionRepository.findAllByMemberId(memberId);
    }

    /** Retry fetching accounts for a FAILED requisition using the stored session_id. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> retrySync(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));

        log.info("Retrying sync for {} (session={})", req.getInstitutionName(), req.getRequisitionId());
        ensureLogoUrl(req);

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        } catch (SyncException ex) {
            req.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(req);
            throw ex;
        }

        FamilyMember member = req.getMember();

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member))
            .flatMap(Optional::stream)
            .toList();

        if (markRetryableIfEmpty(req, accountDataList, "retry")) {
            return responses;
        }

        req.setStatus(RequisitionStatus.LINKED);
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);

        log.info("Retry sync OK for {}: {} accounts linked", req.getInstitutionName(), responses.size());
        return responses;
    }

    /** Delete a requisition (cancel or remove a bank connection). */
    public void deleteRequisition(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));
        requisitionRepository.delete(req);
        log.info("Deleted requisition {}", id);
    }

    /** Retry all FAILED Enable Banking sessions for a member (called by scheduler). */
    public void retryAllFailed(Long memberId) {
        List<Requisition> failed = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.FAILED, memberId);
        for (Requisition req : failed) {
            try {
                retrySync(req.getId(), memberId);
            } catch (Exception ex) {
                log.warn("Scheduled retry failed for {} (session={}): {}",
                    req.getInstitutionName(), req.getRequisitionId(), ex.getMessage());
            }
        }
    }

    /** Re-sync all LINKED requisitions for a specific member (called by scheduler). */
    public void resyncAll(Long memberId) {
        List<Requisition> linked = requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId);
        for (Requisition req : linked) {
            try {
                ensureLogoUrl(req);
                List<BankConnectorPort.AccountData> accounts = bankConnector.fetchBalances(req.getRequisitionId());
                if (markRetryableIfEmpty(req, accounts, "resync")) {
                    continue;
                }
                FamilyMember member = req.getMember();
                accounts.forEach(data -> upsertAccount(data, req, member));
                req.setLastSyncedAt(Instant.now());
                requisitionRepository.save(req);
                log.info("Auto-resync OK for {}: {} accounts", req.getInstitutionName(), accounts.size());
            } catch (Exception ex) {
                req.setStatus(RequisitionStatus.FAILED);
                requisitionRepository.save(req);
                log.warn("Auto-resync failed for {}: {}", req.getInstitutionName(), ex.getMessage());
            }
        }
    }

    /** Refresh balances for the most recent LINKED session for a member. */
    private List<AccountResponse> resyncLatest(Long memberId) {
        Requisition req = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No linked session found to refresh."));

        ensureLogoUrl(req);
        FamilyMember member = req.getMember();

        List<BankConnectorPort.AccountData> accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        if (markRetryableIfEmpty(req, accountDataList, "refresh")) {
            return List.of();
        }
        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member))
            .flatMap(Optional::stream)
            .toList();
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);
        log.info("Refreshed {} accounts for {}", responses.size(), req.getInstitutionName());
        return responses;
    }

    // --- Private ---

    /**
     * Enable Banking can return an empty account list while the provider is still
     * linking accounts asynchronously. Treating that as success hides the retry
     * button and makes the UI claim "synced" even though no account changed.
     */
    private boolean markRetryableIfEmpty(
        Requisition requisition,
        List<BankConnectorPort.AccountData> accountDataList,
        String operation
    ) {
        if (!accountDataList.isEmpty()) return false;

        // An already-LINKED session that suddenly returns no accounts is more likely a
        // transient provider gap than a broken link. Demoting it would make the status
        // flap LINKED → FAILED on every scheduled resync — keep it LINKED and just skip.
        if (requisition.getStatus() == RequisitionStatus.LINKED) {
            log.warn("Enable Banking session {} returned no accounts during {} — keeping LINKED, skipping update",
                requisition.getRequisitionId(), operation);
            return true;
        }

        requisition.setStatus(RequisitionStatus.FAILED);
        requisitionRepository.save(requisition);
        log.info("Enable Banking session {} returned no accounts during {} — marking retryable",
            requisition.getRequisitionId(), operation);
        return true;
    }

    /**
     * Best-effort backfill for requisitions created before bank logos were captured
     * (or whose logo lookup missed the first time): re-searches institutions, scoped
     * to the requisition's own country, and stores the match's logo, if any.
     *
     * <p>Bounded to a single attempt per requisition via {@code logoBackfillAttemptedAt}
     * — a miss (renamed institution, no provider logo) is not retried on every
     * resync/retry forever. The marker is only set once the search call actually
     * completes, so a transient network failure can still be retried next sync.
     */
    private void ensureLogoUrl(Requisition req) {
        if (req.getLogoUrl() != null || req.getLogoBackfillAttemptedAt() != null) return;
        try {
            String country = parseCountry(req.getInstitutionId());
            List<BankConnectorPort.InstitutionData> matches = bankConnector.searchInstitutions(req.getInstitutionName(), country);
            req.setLogoBackfillAttemptedAt(Instant.now());
            findInstitution(matches, req.getInstitutionId(), req.getInstitutionName())
                .map(BankConnectorPort.InstitutionData::logoUrl)
                .ifPresent(req::setLogoUrl);
        } catch (Exception ex) {
            log.warn("Could not backfill logo for requisition {} ({}): {}", req.getId(), req.getInstitutionName(), ex.getMessage());
        }
    }

    /**
     * Resolves a bank's logo at connection-initiation time from the server's own
     * institution catalog — the client-supplied logoUrl is never trusted/persisted,
     * since nothing between an arbitrary client-supplied URL and the Accounts page
     * `<img src>` would validate its scheme or host.
     */
    private String resolveLogoUrl(String institutionId, String institutionName) {
        try {
            List<BankConnectorPort.InstitutionData> matches =
                bankConnector.searchInstitutions(institutionName, parseCountry(institutionId));
            return findInstitution(matches, institutionId, institutionName)
                .map(BankConnectorPort.InstitutionData::logoUrl)
                .orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve logo for institution {} ({}): {}", institutionId, institutionName, ex.getMessage());
            return null;
        }
    }

    /** Matches by exact institution id first; falls back to a case-insensitive name match only if no id match exists. */
    private static Optional<BankConnectorPort.InstitutionData> findInstitution(
        List<BankConnectorPort.InstitutionData> candidates, String institutionId, String institutionName
    ) {
        return candidates.stream()
            .filter(i -> i.id().equals(institutionId))
            .findFirst()
            .or(() -> candidates.stream()
                .filter(i -> i.name().equalsIgnoreCase(institutionName))
                .findFirst());
    }

    /** institutionId format: "BankName::FR" (name::country) — see EnableBankingBankConnector. */
    private static String parseCountry(String institutionId) {
        if (institutionId == null) return null;
        String[] parts = institutionId.split("::");
        return parts.length > 1 ? parts[1] : null;
    }

    /**
     * Returns {@link Optional#empty()} when the matching account was soft-deleted
     * by the user — we must not resurrect it on the next sync. The bank may keep
     * returning the same external id forever; that's not consent to bring it back.
     */
    private Optional<AccountResponse> upsertAccount(BankConnectorPort.AccountData data, Requisition requisition, FamilyMember member) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), member.getId());

        if (existing.isEmpty() &&
            accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), member.getId())) {
            log.info("Skipping resurrection of soft-deleted account externalId={} member={}",
                data.externalId(), member.getId());
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
            if (account.getLogoUrl() == null && requisition.getLogoUrl() != null) {
                account.setLogoUrl(requisition.getLogoUrl());
            }
        } else {
            account = Account.builder()
                .member(member)
                .name(data.name() != null ? data.name() : "Account")
                .type(AccountType.CHECKING)
                .provider(requisition.getInstitutionName())
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color("#6366f1")
                .logoUrl(requisition.getLogoUrl())
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balance(), LocalDate.now());

        return Optional.of(accountService.toResponse(account));
    }

    public record InitiateResponse(String requisitionId, String authLink) {}
}
