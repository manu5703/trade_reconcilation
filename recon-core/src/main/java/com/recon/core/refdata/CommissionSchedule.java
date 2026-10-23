package com.recon.core.refdata;

import com.recon.core.model.AssetClass;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The commission a counterparty should charge on one booking. Internal trades don't carry a
 * commission, so the matcher compares each confirm's commission with this schedule.
 */
public final class CommissionSchedule {

    private CommissionSchedule() {
    }

    /**
     * Equities pay {@code bps} of principal, rounded to cents per booking. Bonds trade net (the
     * dealer's markup is in the price), so their commission is zero.
     */
    public static BigDecimal scheduled(AssetClass assetClass, BigDecimal bps, BigDecimal principal) {
        if (assetClass == AssetClass.FIXED_INCOME) {
            return BigDecimal.ZERO.setScale(2);
        }
        return principal.multiply(bps).movePointLeft(4).setScale(2, RoundingMode.HALF_EVEN);
    }
}
