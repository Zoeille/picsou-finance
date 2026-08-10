package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.BoursoSyncService.AuthInitResponse;
import com.picsou.service.BoursoSyncService.SessionStatusResponse;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bourso")
public class BoursoController {

    private final BoursoSyncService   boursoService;
    private final UserContext          userContext;
    private final Map<String, Bucket>  boursoAuthBuckets;

    public BoursoController(
        BoursoSyncService boursoService,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("boursoAuthBuckets") Map<String, Bucket> boursoAuthBuckets
    ) {
        this.boursoService     = boursoService;
        this.userContext       = userContext;
        this.boursoAuthBuckets = boursoAuthBuckets;
    }

    /**
     * Step 1: Authenticate with BoursoBank.
     * - No MFA: session is stored immediately, returns {mfaRequired: false}.
     * - MFA required: returns {mfaRequired: true, processId, mfaType, contact}.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiateAuth(
        @RequestBody InitiateAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many authentication attempts. Please wait a moment and try again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        AuthInitResponse init = boursoService.initiateAuth(req.customerId(), req.password(), userContext.currentMemberId());
        return ResponseEntity.ok(init);
    }

    /**
     * Step 2 (MFA only): Submit the OTP code to complete authentication.
     * Not called when mfaRequired was false.
     */
    @PostMapping("/auth/complete")
    public SessionStatusResponse completeAuth(@RequestBody CompleteAuthRequest req) {
        return boursoService.completeAuth(req.processId(), req.code(), userContext.currentMemberId());
    }

    /** Manually trigger a sync using the stored session. */
    @PostMapping("/sync")
    public List<AccountResponse> sync() {
        return boursoService.sync(userContext.currentMemberId());
    }

    /** Return session status (active, expiry). */
    @GetMapping("/status")
    public SessionStatusResponse getStatus() {
        return boursoService.getSessionStatus(userContext.currentMemberId());
    }

    /** Clear the stored session (forces re-authentication). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession() {
        boursoService.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // ─── Rate limiting ────────────────────────────────────────────────────────

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = boursoAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createBoursoAuthBucket());
        return bucket.tryConsume(1);
    }

    record InitiateAuthRequest(String customerId, String password) {}

    record CompleteAuthRequest(String processId, String code) {}
}
