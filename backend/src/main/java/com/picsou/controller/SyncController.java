package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.model.Requisition;
import com.picsou.port.BankConnectorPort;
import com.picsou.service.SyncService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;
    private final Map<String, Bucket> syncBuckets;

    public SyncController(
        SyncService syncService,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.syncService = syncService;
        this.syncBuckets = syncBuckets;
    }

    @GetMapping("/institutions")
    public List<BankConnectorPort.InstitutionData> searchInstitutions(
        @RequestParam(required = false, defaultValue = "") String query,
        @RequestParam(required = false, defaultValue = "FR") String country
    ) {
        return syncService.searchInstitutions(query, country);
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(
        @RequestBody InitiateRequest req,
        HttpServletRequest httpReq
    ) {
        if (!checkSyncRateLimit(httpReq)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many sync requests. Please wait a moment.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        SyncService.InitiateResponse response = syncService.initiateConnection(
            req.institutionId(),
            req.institutionName()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/complete")
    public List<AccountResponse> complete(@RequestParam String code) {
        return syncService.completeConnection(code);
    }

    @GetMapping("/status")
    public List<Requisition> getStatus() {
        return syncService.getAllRequisitions();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        List<AccountResponse> accounts = syncService.retrySync(id);
        return ResponseEntity.ok(accounts);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRequisition(@PathVariable Long id) {
        syncService.deleteRequisition(id);
        return ResponseEntity.noContent().build();
    }

    private boolean checkSyncRateLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    record InitiateRequest(String institutionId, String institutionName) {}
}
