package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.ExchangeType;
import com.picsou.service.CryptoExchangeSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/exchange")
public class CryptoExchangeController {

    private final CryptoExchangeSyncService exchangeService;

    public CryptoExchangeController(CryptoExchangeSyncService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @PostMapping
    public AccountResponse addExchange(@RequestBody AddExchangeRequest req) {
        return exchangeService.addExchange(req.type(), req.apiKey(), req.apiSecret());
    }

    @PostMapping("/{id}/sync")
    public AccountResponse sync(@PathVariable Long id) {
        return exchangeService.sync(id);
    }

    @GetMapping("/status")
    public List<CryptoExchangeSyncService.ExchangeStatusResponse> getStatus() {
        return exchangeService.getStatus();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeExchange(@PathVariable Long id) {
        exchangeService.removeExchange(id);
        return ResponseEntity.noContent().build();
    }

    record AddExchangeRequest(ExchangeType type, String apiKey, String apiSecret) {}
}
