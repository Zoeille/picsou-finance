package com.picsou.dto;

import com.picsou.model.SharingLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record SharingSettingsRequest(
    @NotBlank @Pattern(regexp = "ACCOUNT|GOAL") String resourceType,
    @NotNull SharingLevel sharingLevel,
    List<@NotNull Long> sharedResourceIds
) {}
