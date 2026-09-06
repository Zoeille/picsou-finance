package com.picsou.service;

import com.picsou.repository.FamilyMemberRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Process-local preview data bound to the member that created it.
 *
 * <p>Preview registration and deletion cleanup share one monitor. Registration verifies that
 * the member still exists while holding that monitor; a preview which finishes after a committed
 * deletion therefore cannot leave data in the cache. This intentionally keeps no deletion
 * tombstones: the database remains the source of truth.
 */
public final class MemberPreviewCache<T> {

    private final ConcurrentHashMap<String, T> entries = new ConcurrentHashMap<>();
    private final FamilyMemberRepository memberRepository;
    private final Function<T, Long> memberId;
    private final Function<T, Instant> createdAt;
    private final Clock clock;
    private final Object monitor = new Object();

    public MemberPreviewCache(
        FamilyMemberRepository memberRepository,
        Function<T, Long> memberId,
        Function<T, Instant> createdAt
    ) {
        this(memberRepository, memberId, createdAt, Clock.systemUTC());
    }

    MemberPreviewCache(
        FamilyMemberRepository memberRepository,
        Function<T, Long> memberId,
        Function<T, Instant> createdAt,
        Clock clock
    ) {
        this.memberRepository = memberRepository;
        this.memberId = memberId;
        this.createdAt = createdAt;
        this.clock = clock;
    }

    /**
     * Stores a preview only while its member still exists.
     *
     * @return false when deletion completed before this preview could be registered
     */
    public boolean register(String token, T preview) {
        synchronized (monitor) {
            if (!memberRepository.existsById(memberId.apply(preview))) {
                return false;
            }
            entries.put(token, preview);
            return true;
        }
    }

    /**
     * Reads a still-fresh preview belonging to {@code requestedMemberId}.
     *
     * <p>Ownership mismatches deliberately look the same as absent or expired tokens.
     */
    public Optional<T> find(String token, Long requestedMemberId, Duration ttl) {
        synchronized (monitor) {
            return findLocked(token, requestedMemberId, ttl);
        }
    }

    /** Reads and removes a still-fresh preview belonging to {@code requestedMemberId}. */
    public Optional<T> consume(String token, Long requestedMemberId, Duration ttl) {
        synchronized (monitor) {
            Optional<T> preview = findLocked(token, requestedMemberId, ttl);
            preview.ifPresent(ignored -> entries.remove(token));
            return preview;
        }
    }

    private Optional<T> findLocked(String token, Long requestedMemberId, Duration ttl) {
        T preview = entries.get(token);
        if (preview == null) {
            return Optional.empty();
        }
        if (isExpired(preview, ttl)) {
            entries.remove(token);
            return Optional.empty();
        }
        if (!Objects.equals(memberId.apply(preview), requestedMemberId)) {
            return Optional.empty();
        }
        return Optional.of(preview);
    }

    /** Removes every preview created by a deleted member. */
    public void discardForMember(Long deletedMemberId) {
        synchronized (monitor) {
            entries.entrySet().removeIf(entry -> Objects.equals(memberId.apply(entry.getValue()), deletedMemberId));
        }
    }

    /** Removes entries whose lifetime has elapsed, for the scheduled housekeeping sweep. */
    public void discardExpired(Duration ttl) {
        synchronized (monitor) {
            entries.entrySet().removeIf(entry -> isExpired(entry.getValue(), ttl));
        }
    }

    private boolean isExpired(T preview, Duration ttl) {
        return !createdAt.apply(preview).plus(ttl).isAfter(Instant.now(clock));
    }
}
