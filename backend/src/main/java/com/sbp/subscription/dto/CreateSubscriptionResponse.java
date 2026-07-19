package com.sbp.subscription.dto;

import java.util.UUID;

public record CreateSubscriptionResponse(UUID subscriptionId, String checkoutUrl) {}
