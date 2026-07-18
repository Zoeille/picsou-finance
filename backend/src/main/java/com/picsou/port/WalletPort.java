package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

public interface WalletPort {

    String chain();

    /**
     * Rejects an address this chain can never resolve, <em>before</em> it is persisted.
     * Called by {@code WalletSyncService.addWallet}: without it a typo is stored, the
     * first sync fails, and the unusable row lingers and fails every later resync.
     *
     * <p>Throws {@link IllegalArgumentException} (surfaced as HTTP 400) when the format
     * is wrong. The default accepts anything — a chain whose format is not cheaply
     * checkable offline (Bitcoin's several encodings, Solana's base58) keeps deferring
     * to the RPC call, which is the current behaviour for those adapters.
     */
    default void validateAddress(String address) {
        // No offline format check for this chain -- fetchBalances is the gate.
    }

    /**
     * Returns one entry per asset held at this address. Always at least one
     * entry for the chain's native asset (SOL, ETH, BTC...) — even if zero —
     * plus one entry per non-zero token (SPL on Solana, ERC-20 on Ethereum…).
     */
    List<WalletBalance> fetchBalances(String address);

    record WalletBalance(String symbol, BigDecimal amount) {}
}
