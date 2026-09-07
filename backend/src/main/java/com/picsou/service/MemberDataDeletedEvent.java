package com.picsou.service;

/**
 * Published after a member's owned data has been deleted.
 *
 * <p>Listeners use {@code AFTER_COMMIT} so process-local state is cleared only when the
 * database deletion is durable.
 */
public record MemberDataDeletedEvent(Long memberId) {}
