package com.picsou.controller;

import com.picsou.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping
    public Map<String, BigDecimal> getPrices(@RequestParam String tickers) {
        Set<String> tickerSet = Arrays.stream(tickers.split(","))
            .map(String::trim)
            .filter(t -> !t.isBlank())
            .collect(Collectors.toSet());

        return priceService.refreshPrices(tickerSet);
    }
}
