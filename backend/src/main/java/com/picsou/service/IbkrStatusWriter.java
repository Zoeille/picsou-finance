package com.picsou.service;

import com.picsou.repository.IbkrConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an IBKR connection's {@code ERROR} status in its own transaction.
 *
 * <p>{@link IbkrSyncService} is {@code @Transactional}; when a manual sync fails, the
 * rethrown exception marks that transaction rollback-only, so a plain save of the
 * {@code ERROR} status made inside {@code syncWithConnection} is silently discarded on
 * the manual path (the scheduled path happens to survive because it catches the
 * exception before it crosses the proxy — the two paths must not disagree). This bean's
 * {@code REQUIRES_NEW} method commits independently of the caller's transaction, so the
 * status write survives regardless of how the caller handles the exception. Must be a
 * separate Spring bean: a {@code REQUIRES_NEW} method invoked via {@code this} inside
 * {@code IbkrSyncService} would not cross the proxy and would have no effect.
 */
@Service
@RequiredArgsConstructor
public class IbkrStatusWriter {

    private final IbkrConnectionRepository connectionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long connectionId) {
        connectionRepository.findById(connectionId).ifPresent(c -> {
            c.setStatus("ERROR");
            connectionRepository.save(c);
        });
    }
}
