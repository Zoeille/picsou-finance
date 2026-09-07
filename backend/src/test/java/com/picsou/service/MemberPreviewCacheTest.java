package com.picsou.service;

import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberPreviewCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Mock FamilyMemberRepository memberRepository;

    private MemberPreviewCache<Preview> cache;

    @BeforeEach
    void setUp() {
        cache = new MemberPreviewCache<>(
            memberRepository,
            Preview::memberId,
            Preview::createdAt,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void register_refusesPreviewThatFinishesAfterMemberDeletion() {
        when(memberRepository.existsById(1L)).thenReturn(false);

        boolean registered = cache.register("preview", preview(1L, NOW));

        assertThat(registered).isFalse();
        assertThat(cache.consume("preview", 1L, TEN_MINUTES)).isEmpty();
    }

    @Test
    void consume_requiresTheMemberThatCreatedThePreview() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        cache.register("preview", preview(1L, NOW));

        Optional<Preview> foreignMember = cache.consume("preview", 2L, TEN_MINUTES);
        Optional<Preview> owner = cache.consume("preview", 1L, TEN_MINUTES);

        assertThat(foreignMember).isEmpty();
        assertThat(owner).contains(preview(1L, NOW));
    }

    @Test
    void consume_rejectsAnEntryAtItsExpiryBoundary() {
        when(memberRepository.existsById(1L)).thenReturn(true);
        cache.register("preview", preview(1L, NOW.minus(TEN_MINUTES)));

        Optional<Preview> consumed = cache.consume("preview", 1L, TEN_MINUTES);

        assertThat(consumed).isEmpty();
        assertThat(cache.consume("preview", 1L, TEN_MINUTES)).isEmpty();
    }

    @Test
    void discardForMember_keepsOtherMembersPreviews() {
        when(memberRepository.existsById(anyLong())).thenReturn(true);
        cache.register("deleted", preview(1L, NOW));
        cache.register("survivor", preview(2L, NOW));

        cache.discardForMember(1L);

        assertThat(cache.consume("deleted", 1L, TEN_MINUTES)).isEmpty();
        assertThat(cache.consume("survivor", 2L, TEN_MINUTES)).contains(preview(2L, NOW));
    }

    private Preview preview(Long memberId, Instant createdAt) {
        return new Preview(memberId, createdAt);
    }

    private record Preview(Long memberId, Instant createdAt) {}
}
