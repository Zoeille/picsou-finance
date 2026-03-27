package com.picsou.port;

import java.math.BigDecimal;

public interface WalletPort {

    String chain();

    WalletBalance fetchBalance(String address);

    record WalletBalance(String nativeSymbol, BigDecimal nativeAmount) {}
}
