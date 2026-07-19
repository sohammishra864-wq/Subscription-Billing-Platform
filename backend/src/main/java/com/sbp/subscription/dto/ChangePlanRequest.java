package com.sbp.subscription.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChangePlanRequest(@NotNull UUID newPlanId) {}
