package com.picsou.service;

import com.picsou.dto.OwnershipRequest;
import com.picsou.dto.OwnershipResponse;
import com.picsou.dto.OwnershipResponse.MemberShare;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountOwnership;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountOwnershipRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes how an account is split between family members.
 *
 * <p>Restricted on purpose to {@code REAL_ESTATE} and {@code LOAN}. Splitting a joint current
 * account sounds reasonable but immediately raises questions this feature does not answer —
 * whose transactions are they, who syncs it, what does a half-transaction mean — so the
 * write path refuses other types rather than half-supporting them.
 */
@Service
public class AccountOwnershipService {

    private static final BigDecimal FULL = new BigDecimal("100");

    /** Types a split is meaningful for today. */
    private static final Set<AccountType> SPLITTABLE = Set.of(AccountType.REAL_ESTATE, AccountType.LOAN);

    private final AccountOwnershipRepository ownershipRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountAccessResolver accessResolver;

    public AccountOwnershipService(AccountOwnershipRepository ownershipRepository,
                                   FamilyMemberRepository memberRepository,
                                   AccountAccessResolver accessResolver) {
        this.ownershipRepository = ownershipRepository;
        this.memberRepository = memberRepository;
        this.accessResolver = accessResolver;
    }

    /** Readable by co-owners: knowing your own share means seeing the whole split. */
    @Transactional(readOnly = true)
    public OwnershipResponse get(Long accountId, Long memberId) {
        Account account = accessResolver.requireReadable(accountId, memberId);
        return toResponse(account, ownershipRepository.findByAccountId(accountId));
    }

    /**
     * Replaces the split. Owner-only — a co-owner reallocating shares could hand themselves
     * the whole property.
     */
    @Transactional
    public OwnershipResponse replace(Long accountId, Long memberId, OwnershipRequest request) {
        Account account = accessResolver.requireOwner(accountId, memberId);

        if (!SPLITTABLE.contains(account.getType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Ownership shares are only supported on real estate and loan accounts");
        }

        List<OwnershipRequest.Share> shares = request.shares();
        ownershipRepository.deleteAllForAccount(accountId);

        if (shares.isEmpty()) {
            // Clearing the split restores the implicit 100% for the owner.
            return toResponse(account, List.of());
        }

        validate(account, shares);

        List<AccountOwnership> rows = new ArrayList<>(shares.size());
        for (OwnershipRequest.Share share : shares) {
            FamilyMember member = memberRepository.findById(share.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            rows.add(AccountOwnership.builder()
                .account(account)
                .member(member)
                .sharePercent(share.sharePercent())
                .build());
        }
        return toResponse(account, ownershipRepository.saveAll(rows));
    }

    private void validate(Account account, List<OwnershipRequest.Share> shares) {
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OwnershipRequest.Share share : shares) {
            if (!seen.add(share.memberId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A member appears twice in the ownership split");
            }
            total = total.add(share.sharePercent());
        }

        // Over 100% would inflate the family's combined net worth out of thin air. Under 100%
        // is legitimate — the rest is held outside Picsou — so only the upper bound is an error.
        if (total.compareTo(FULL) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Ownership shares add up to more than 100%");
        }

        Long ownerId = account.getMember().getId();
        if (!seen.contains(ownerId)) {
            // The owner stays responsible for the account (editing, syncing, deleting), so a
            // split that writes them out entirely is almost certainly a mistake. Transferring
            // a property means moving the account, not zeroing its owner.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "The account owner must keep a share");
        }
    }

    private OwnershipResponse toResponse(Account account, List<AccountOwnership> rows) {
        Long ownerId = account.getMember().getId();

        if (rows.isEmpty()) {
            FamilyMember owner = account.getMember();
            return new OwnershipResponse(
                List.of(new MemberShare(ownerId, owner.getDisplayName(), owner.getAvatarColor(), FULL, true)),
                FULL,
                BigDecimal.ZERO
            );
        }

        List<MemberShare> shares = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (AccountOwnership row : rows) {
            FamilyMember member = row.getMember();
            shares.add(new MemberShare(
                member.getId(),
                member.getDisplayName(),
                member.getAvatarColor(),
                row.getSharePercent(),
                member.getId().equals(ownerId)
            ));
            total = total.add(row.getSharePercent());
        }
        return new OwnershipResponse(shares, total, FULL.subtract(total));
    }
}
