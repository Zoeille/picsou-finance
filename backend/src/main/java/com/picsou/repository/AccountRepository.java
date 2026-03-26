package com.picsou.repository;

import com.picsou.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findAllByOrderByCreatedAtAsc();
    Optional<Account> findByExternalAccountId(String externalAccountId);
    List<Account> findByTickerIsNotNull();
}
