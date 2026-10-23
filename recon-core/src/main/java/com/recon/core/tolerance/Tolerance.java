package com.recon.core.tolerance;

import static com.recon.core.Checks.nonNegative;

import java.math.BigDecimal;

/**
 * Matching tolerances for one asset class.
 *
 * <p>Price and net amount use a band of {@code max(abs, |expected| * bps / 10,000)}: the absolute
 * floor covers cheap instruments and small trades, the relative part scales with size. Commission
 * is compared absolutely. Differences exactly on the band edge are within tolerance. The band is
 * always computed from the expected (internal) value so the result doesn't depend on which side
 * is wrong.
 */
public record Tolerance(
        BigDecimal priceAbs,
        BigDecimal priceBps,
        BigDecimal netAmountAbs,
        BigDecimal netAmountBps,
        BigDecimal commissionAbs) {

    public Tolerance {
        nonNegative(priceAbs, "priceAbs");
        nonNegative(priceBps, "priceBps");
        nonNegative(netAmountAbs, "netAmountAbs");
        nonNegative(netAmountBps, "netAmountBps");
        nonNegative(commissionAbs, "commissionAbs");
    }

    public BigDecimal priceTolerance(BigDecimal expectedPrice) {
        return band(priceAbs, priceBps, expectedPrice);
    }

    public BigDecimal netAmountTolerance(BigDecimal expectedNetAmount) {
        return band(netAmountAbs, netAmountBps, expectedNetAmount);
    }

    public boolean priceWithin(BigDecimal expected, BigDecimal actual) {
        return within(expected, actual, priceTolerance(expected));
    }

    public boolean netAmountWithin(BigDecimal expected, BigDecimal actual) {
        return within(expected, actual, netAmountTolerance(expected));
    }

    public boolean commissionWithin(BigDecimal expected, BigDecimal actual) {
        return within(expected, actual, commissionAbs);
    }

    private static BigDecimal band(BigDecimal abs, BigDecimal bps, BigDecimal expected) {
        // movePointLeft(4) divides by 10,000 exactly; no rounding mode needed.
        BigDecimal relative = expected.abs().multiply(bps).movePointLeft(4);
        return abs.max(relative);
    }

    private static boolean within(BigDecimal expected, BigDecimal actual, BigDecimal tolerance) {
        return expected.subtract(actual).abs().compareTo(tolerance) <= 0;
    }
}
