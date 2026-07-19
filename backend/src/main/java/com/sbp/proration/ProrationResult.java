package com.sbp.proration;

import java.util.List;

public record ProrationResult(
        long creditCents,
        long debitCents,
        long netAmountCents,
        int remainingDays,
        int totalDays,
        List<ProrationLineItem> lineItems
) {}
