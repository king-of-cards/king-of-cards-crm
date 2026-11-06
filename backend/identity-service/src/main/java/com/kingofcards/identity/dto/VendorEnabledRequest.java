package com.kingofcards.identity.dto;

import jakarta.validation.constraints.NotNull;

public record VendorEnabledRequest(@NotNull Boolean enabled) {}
