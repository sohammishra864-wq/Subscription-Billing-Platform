package com.sbp.proration;

import com.sbp.plan.Plan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ProrationCalculator {

    public ProrationResult calculate(Plan oldPlan, Plan newPlan,
                                     Instant periodStart, Instant periodEnd, Instant changeAt) {
        LocalDate startDate = periodStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endDate = periodEnd.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate changeDate = changeAt.atZone(ZoneOffset.UTC).toLocalDate();

        int totalDays = (int) ChronoUnit.DAYS.between(startDate, endDate);
        if (totalDays <= 0) {
            throw new IllegalStateException("Invalid billing period");
        }

        int remainingDays = (int) ChronoUnit.DAYS.between(changeDate, endDate);
        remainingDays = Math.max(0, Math.min(remainingDays, totalDays));

        // Single division, HALF_EVEN rounding — no compounded per-day rounding
        long creditCents = BigDecimal.valueOf(oldPlan.getPriceCents())
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_EVEN)
                .longValue();

        long debitCents = BigDecimal.valueOf(newPlan.getPriceCents())
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_EVEN)
                .longValue();

        long netAmountCents = debitCents - creditCents;

        var lineItems = List.of(
                new ProrationLineItem("Unused time on " + oldPlan.getName(), -creditCents, "PRORATION_CREDIT"),
                new ProrationLineItem("Remaining time on " + newPlan.getName(), debitCents, "PRORATION_DEBIT")
        );

        return new ProrationResult(creditCents, debitCents, netAmountCents, remainingDays, totalDays, lineItems);
    }
}
