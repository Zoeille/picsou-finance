package com.picsou.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GoalRequest(
    @NotBlank @Size(max = 200) String name,
    @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
    @NotNull @Future LocalDate deadline,
    @NotEmpty List<Long> accountIds
) {}
