package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountDeletionResponse;
import com.picsou.dto.DeleteAccountRequest;
import com.picsou.model.AccountDeletionMode;
import com.picsou.model.AppUser;
import com.picsou.service.FamilyService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeDeletionController {

    private static final Logger log = LoggerFactory.getLogger(MeDeletionController.class);

    private final FamilyService familyService;
    private final AuthCookieWriter cookieWriter;
    private final Map<String, Bucket> deleteBuckets;

    public MeDeletionController(
        FamilyService familyService,
        AuthCookieWriter cookieWriter,
        @Qualifier("deleteBuckets") Map<String, Bucket> deleteBuckets
    ) {
        this.familyService = familyService;
        this.cookieWriter = cookieWriter;
        this.deleteBuckets = deleteBuckets;
    }

    @GetMapping("/deletion-impact")
    public AccountDeletionResponse deletionImpact(@AuthenticationPrincipal AppUser user) {
        return new AccountDeletionResponse(familyService.previewOwnAccountDeletion(user.getId()));
    }

    @DeleteMapping
    public ResponseEntity<?> deleteOwnAccount(
        @AuthenticationPrincipal AppUser user,
        @RequestBody DeleteAccountRequest req,
        HttpServletResponse httpRes
    ) {
        String key = String.valueOf(user.getId());
        Bucket bucket = deleteBuckets.computeIfAbsent(key, k -> RateLimitConfig.createDeleteBucket());
        if (!bucket.tryConsume(1)) {
            log.warn("account.deletion.rate_limited userId={}", user.getId());
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many account deletion attempts. Try again later.");
            problem.setProperty("code", "ACCOUNT_DELETION_RATE_LIMITED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
        }

        AccountDeletionMode mode = familyService.deleteOwnAccount(user.getId(), req.reAuth());
        cookieWriter.clearAuthCookies(httpRes);

        log.warn("account.deletion.completed userId={} mode={}", user.getId(), mode);
        return ResponseEntity.ok(new AccountDeletionResponse(mode));
    }
}
