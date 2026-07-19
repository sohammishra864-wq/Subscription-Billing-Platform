package com.sbp.proration;

import com.sbp.plan.BillingInterval;
import com.sbp.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProrationCalculatorTest {

    private final ProrationCalculator calculator = new ProrationCalculator();

    private Plan plan(String name, long priceCents) {
        return new Plan(name, "price_" + name, priceCents, "USD", BillingInterval.MONTHLY);
    }

    private Instant dateToInstant(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    @Test
    void midCycleUpgrade() {
        var result = calculator.calculate(
                plan("Basic", 1000), plan("Pro", 3000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 16));
        // 15 remaining days out of 30
        assertThat(result.remainingDays()).isEqualTo(15);
        assertThat(result.totalDays()).isEqualTo(30);
        assertThat(result.creditCents()).isEqualTo(500);
        assertThat(result.debitCents()).isEqualTo(1500);
        assertThat(result.netAmountCents()).isEqualTo(1000);
    }

    @Test
    void midCycleDowngrade() {
        var result = calculator.calculate(
                plan("Pro", 3000), plan("Basic", 1000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 16));
        assertThat(result.creditCents()).isEqualTo(1500);
        assertThat(result.debitCents()).isEqualTo(500);
        assertThat(result.netAmountCents()).isEqualTo(-1000);
    }

    @Test
    void changeOnFirstDay() {
        var result = calculator.calculate(
                plan("Basic", 1000), plan("Pro", 3000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 1));
        // Full period remaining
        assertThat(result.remainingDays()).isEqualTo(30);
        assertThat(result.creditCents()).isEqualTo(1000);
        assertThat(result.debitCents()).isEqualTo(3000);
    }

    @Test
    void changeOnLastDay() {
        var result = calculator.calculate(
                plan("Basic", 1000), plan("Pro", 3000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 31));
        // 0 remaining days
        assertThat(result.remainingDays()).isEqualTo(0);
        assertThat(result.creditCents()).isEqualTo(0);
        assertThat(result.debitCents()).isEqualTo(0);
        assertThat(result.netAmountCents()).isEqualTo(0);
    }

    @Test
    void leapYearFebruary() {
        var result = calculator.calculate(
                plan("Basic", 2900), plan("Pro", 2900),
                dateToInstant(2024, 2, 1), dateToInstant(2024, 3, 1),
                dateToInstant(2024, 2, 15));
        // Feb 2024 is 29 days (leap year), period is Feb 1 to Mar 1 = 29 days
        assertThat(result.totalDays()).isEqualTo(29);
    }

    @Test
    void samePriceLateralMove() {
        var result = calculator.calculate(
                plan("PlanA", 2000), plan("PlanB", 2000),
                dateToInstant(2025, 3, 1), dateToInstant(2025, 3, 31),
                dateToInstant(2025, 3, 15));
        assertThat(result.netAmountCents()).isEqualTo(0);
    }

    @Test
    void annualPeriod() {
        var result = calculator.calculate(
                plan("Annual Basic", 10000), plan("Annual Pro", 20000),
                dateToInstant(2025, 1, 1), dateToInstant(2026, 1, 1),
                dateToInstant(2025, 7, 1));
        // 365-day period, 184 days remaining (Jul 1 to Jan 1)
        assertThat(result.totalDays()).isEqualTo(365);
        assertThat(result.remainingDays()).isEqualTo(184);
    }

    @Test
    void changeAtPeriodBoundary() {
        var result = calculator.calculate(
                plan("Basic", 1000), plan("Pro", 3000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 31));
        // changeAt == periodEnd
        assertThat(result.remainingDays()).isEqualTo(0);
        assertThat(result.netAmountCents()).isEqualTo(0);
    }

    @Test
    void invalidPeriodThrows() {
        assertThatThrownBy(() -> calculator.calculate(
                plan("A", 1000), plan("B", 2000),
                dateToInstant(2025, 1, 31), dateToInstant(2025, 1, 1),
                dateToInstant(2025, 1, 15)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lineItemsGenerated() {
        var result = calculator.calculate(
                plan("Basic", 1000), plan("Pro", 3000),
                dateToInstant(2025, 1, 1), dateToInstant(2025, 1, 31),
                dateToInstant(2025, 1, 16));
        assertThat(result.lineItems()).hasSize(2);
        assertThat(result.lineItems().get(0).type()).isEqualTo("PRORATION_CREDIT");
        assertThat(result.lineItems().get(1).type()).isEqualTo("PRORATION_DEBIT");
    }

    @ParameterizedTest
    @CsvSource({
            "999, 1999, 31, 7",
            "1299, 2499, 28, 14",
            "4999, 9999, 30, 1"
    })
    void roundingStress(long oldPrice, long newPrice, int periodDays, int changeDay) {
        var periodStart = dateToInstant(2025, 1, 1);
        var periodEnd = periodStart.atZone(ZoneOffset.UTC).plusDays(periodDays).toInstant();
        var changeAt = periodStart.atZone(ZoneOffset.UTC).plusDays(changeDay).toInstant();

        var result = calculator.calculate(plan("Old", oldPrice), plan("New", newPrice),
                periodStart, periodEnd, changeAt);

        // Net should equal debit - credit exactly
        assertThat(result.netAmountCents()).isEqualTo(result.debitCents() - result.creditCents());
    }
}
