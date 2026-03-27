package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Chain;
import com.picsou.service.WalletSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/wallet")
public class WalletController {

    private final WalletSyncService walletService;

    public WalletController(WalletSyncService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public AccountResponse addWallet(@RequestBody AddWalletRequest req) {
        return walletService.addWallet(req.chain(), req.address(), req.label());
    }

    @PostMapping("/{id}/sync")
    public AccountResponse sync(@PathVariable Long id) {
        return walletService.sync(id);
    }

    @GetMapping
    public List<WalletSyncService.WalletStatusResponse> listWallets() {
        return walletService.listWallets();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeWallet(@PathVariable Long id) {
        walletService.removeWallet(id);
        return ResponseEntity.noContent().build();
    }

    record AddWalletRequest(Chain chain, String address, String label) {}
}
