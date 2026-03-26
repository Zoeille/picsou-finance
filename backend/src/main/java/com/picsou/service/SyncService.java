package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
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
    private final AccountService accountService;

    public SyncService(
        BankConnectorPort bankConnector,
        AccountRepository accountRepository,
        RequisitionRepository requisitionRepository,
        AccountService accountService
    ) {
        this.bankConnector = bankConnector;
        this.accountRepository = accountRepository;
        this.requisitionRepository = requisitionRepository;
        this.accountService = accountService;
    }

    /** Step 1: Initiate GoCardless bank connection for a given institution. */
    public InitiateResponse initiateConnection(String institutionId, String institutionName) {
        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(institutionId);

        Requisition requisition = Requisition.builder()
            .requisitionId(result.requisitionId())
            .institutionId(institutionId)
            .institutionName(institutionName)
            .status(RequisitionStatus.CREATED)
            .authLink(result.authLink())
            .build();

        requisitionRepository.save(requisition);

        return new InitiateResponse(result.requisitionId(), result.authLink());
    }

    /** Step 2: Complete Enable Banking flow — exchange OAuth code, fetch balances, upsert accounts. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> completeConnection(String oauthCode) {
        // Find the pending requisition (single-user app: take the latest CREATED one)
        Requisition requisition = requisitionRepository
            .findByStatusOrderByCreatedAtDesc(RequisitionStatus.CREATED)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No pending bank connection found. Please initiate a new connection."));

        String sessionId;
        try {
            sessionId = bankConnector.exchangeCode(oauthCode);
        } catch (SyncException ex) {
            // Code already used → find existing linked session and just refresh balances
            if (ex.getMessage().contains("ALREADY_AUTHORIZED")) {
                log.info("Code already used, refreshing latest linked session");
                return resyncLatest();
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

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, requisition.getInstitutionName()))
            .toList();

        requisition.setStatus(RequisitionStatus.LINKED);
        requisitionRepository.save(requisition);

        log.info("Completed Enable Banking sync for {}: {} accounts linked", requisition.getInstitutionName(), responses.size());
        return responses;
    }

    /** Search available institutions. */
    @Transactional(readOnly = true)
    public List<BankConnectorPort.InstitutionData> searchInstitutions(String query, String country) {
        return bankConnector.searchInstitutions(query, country);
    }

    /** Get all requisitions ordered by date. */
    @Transactional(readOnly = true)
    public List<Requisition> getAllRequisitions() {
        return requisitionRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Retry fetching accounts for a FAILED requisition using the stored session_id. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> retrySync(Long id) {
        Requisition req = requisitionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found: " + id));

        log.info("Retrying sync for {} (session={})", req.getInstitutionName(), req.getRequisitionId());

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        } catch (SyncException ex) {
            req.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(req);
            throw ex;
        }

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req.getInstitutionName()))
            .toList();

        req.setStatus(RequisitionStatus.LINKED);
        requisitionRepository.save(req);

        log.info("Retry sync OK for {}: {} accounts linked", req.getInstitutionName(), responses.size());
        return responses;
    }

    /** Delete a requisition (cancel or remove a bank connection). */
    public void deleteRequisition(Long id) {
        if (!requisitionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Requisition not found: " + id);
        }
        requisitionRepository.deleteById(id);
        log.info("Deleted requisition {}", id);
    }

    /** Re-sync all LINKED requisitions (called by scheduler). */
    public void resyncAll() {
        List<Requisition> linked = requisitionRepository.findByStatusOrderByCreatedAtDesc(RequisitionStatus.LINKED);
        for (Requisition req : linked) {
            try {
                List<BankConnectorPort.AccountData> accounts = bankConnector.fetchBalances(req.getRequisitionId());
                accounts.forEach(data -> upsertAccount(data, req.getInstitutionName()));
                log.info("Auto-resync OK for {}: {} accounts", req.getInstitutionName(), accounts.size());
            } catch (Exception ex) {
                log.warn("Auto-resync failed for {}: {}", req.getInstitutionName(), ex.getMessage());
            }
        }
    }

    /** Refresh balances for the most recent LINKED session. */
    private List<AccountResponse> resyncLatest() {
        Requisition req = requisitionRepository
            .findByStatusOrderByCreatedAtDesc(RequisitionStatus.LINKED)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No linked session found to refresh."));

        List<BankConnectorPort.AccountData> accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req.getInstitutionName()))
            .toList();
        log.info("Refreshed {} accounts for {}", responses.size(), req.getInstitutionName());
        return responses;
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private AccountResponse upsertAccount(BankConnectorPort.AccountData data, String provider) {
        Optional<Account> existing = accountRepository.findByGocardlessAccountId(data.externalId());

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
                .name(data.name() != null ? data.name() : "Account")
                .type(AccountType.CHECKING)
                .provider(provider)
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .gocardlessAccountId(data.externalId())
                .isManual(false)
                .color("#6366f1")
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balance(), LocalDate.now());

        return accountService.toResponse(account);
    }

    public record InitiateResponse(String requisitionId, String authLink) {}
}
