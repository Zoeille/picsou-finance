package com.picsou.repository;

import com.picsou.model.CryptoExchangePosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CryptoExchangePositionRepository extends JpaRepository<CryptoExchangePosition, Long> {

    List<CryptoExchangePosition> findByAccountIdOrderByProductAscTickerAsc(Long accountId);

    void deleteByAccountId(Long accountId);
}
