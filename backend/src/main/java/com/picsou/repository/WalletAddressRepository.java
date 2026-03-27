package com.picsou.repository;

import com.picsou.model.WalletAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletAddressRepository extends JpaRepository<WalletAddress, Long> {
    List<WalletAddress> findAllByOrderByCreatedAtAsc();
}
