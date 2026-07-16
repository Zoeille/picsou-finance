package com.picsou.exception;

/**
 * Raised when a blockchain JSON-RPC endpoint returns an error, an empty
 * envelope, or a response missing its {@code result}. Unchecked so wallet
 * adapters keep the {@code WalletPort} signature; {@code WalletSyncService}
 * catches it and re-wraps into a {@link SyncException} (HTTP 422). This exists
 * to stop adapters from silently reporting a 0 balance on RPC failure.
 */
public class WalletRpcException extends RuntimeException {
    public WalletRpcException(String message) {
        super(message);
    }
}
