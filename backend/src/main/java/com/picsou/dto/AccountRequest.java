package com.picsou.dto;

import com.picsou.model.AccountType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record AccountRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull AccountType type,
    @Size(max = 100) String provider,
    @NotBlank @Size(max = 10) String currency,
    @DecimalMin("0") BigDecimal currentBalance,
    boolean isManual,
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color") String color,
    @Size(max = 20) String ticker
) {}
