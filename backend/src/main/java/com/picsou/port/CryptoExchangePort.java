package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

public interface CryptoExchangePort {

    String exchangeName();

    List<CryptoHolding> fetchHoldings(String apiKey, String apiSecret);

    boolean testConnection(String apiKey, String apiSecret);

    record CryptoHolding(String symbol, BigDecimal quantity) {}
}
