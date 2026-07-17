package com.picsou.repository;

import com.picsou.model.AccountHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountHoldingRepository extends JpaRepository<AccountHolding, Long> {

    List<AccountHolding> findByAccountIdOrderByCurrentPriceDesc(Long accountId);

    List<AccountHolding> findByAccount_Id(Long accountId);

    Optional<AccountHolding> findByAccountIdAndTicker(Long accountId, String ticker);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndTickerNotIn(Long accountId, Collection<String> tickers);

    @Query("SELECT DISTINCT h.ticker FROM AccountHolding h WHERE h.ticker IS NOT NULL")
    Set<String> findDistinctTickers();
}
