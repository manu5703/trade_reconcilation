package com.recon.datagen.refdata;

import com.recon.core.model.AssetClass;
import com.recon.core.model.Source;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A broker the firm trades with.
 *
 * @param id             canonical ID used in internal bookings, e.g. CP007
 * @param aliases        other spellings that appear on confirms; all map back to {@code id}
 * @param confirmSource  whether this counterparty's confirms arrive from the broker or via clearing
 * @param commissionBps  equity commission schedule, in basis points of principal
 * @param refPrefix      prefix of the broker's own trade references
 */
public record Counterparty(
        String id,
        String name,
        List<String> aliases,
        Source confirmSource,
        BigDecimal commissionBps,
        String refPrefix) {

    public Counterparty {
        aliases = List.copyOf(aliases);
    }

    /**
     * Commission this counterparty should charge on one allocation: equities pay the schedule
     * rate on principal, rounded to cents; bonds trade net (the dealer's markup is in the price).
     */
    public BigDecimal scheduledCommission(AssetClass assetClass, BigDecimal principal) {
        if (assetClass == AssetClass.FIXED_INCOME) {
            return BigDecimal.ZERO.setScale(2);
        }
        return principal.multiply(commissionBps).movePointLeft(4).setScale(2, RoundingMode.HALF_EVEN);
    }
}
