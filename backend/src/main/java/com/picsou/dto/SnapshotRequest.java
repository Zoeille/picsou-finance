package com.picsou.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SnapshotRequest(
    @NotNull @DecimalMin("0") BigDecimal balance,
    @NotNull LocalDate date
) {}
