package com.picsou.dto;

import com.picsou.model.AccountType;
import com.picsou.validation.ValidCurrency;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record AccountRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull AccountType type,
    @Size(max = 100) String provider,
    @NotBlank @Size(max = 10) @ValidCurrency String currency,
    @DecimalMin("0") BigDecimal currentBalance,
    boolean isManual,
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color") String color,
    @Size(max = 20) String ticker,
    // Which bundled frontend asset the account shows ("blockchain", "ledger"...). Validated as a
    // slug rather than against a fixed list: the assets live in the frontend, so pinning the
    // allowed values here would mean a backend release every time one is added. An unknown key
    // simply resolves to no logo client-side. Null clears the choice.
    @Pattern(regexp = "^[a-z0-9-]{1,32}$", message = "Logo key must be a lowercase slug") String logoKey
) {}
