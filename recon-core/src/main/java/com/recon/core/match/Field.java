package com.recon.core.match;

import com.recon.core.model.BreakType;

/**
 * Fields compared between a booking and a confirm, with the fuzzy-score penalty each one costs
 * when it differs and the break it raises.
 *
 * <p>Penalties are whole points out of 100, so scores are exact integers (no floating-point
 * ties). They're chosen so that a pair with exactly one wrong field, plus the net amount that
 * field drags along, scores at least 75 and clears the threshold of 70, while two wrong fields
 * score at most 65 and are rejected.
 *
 * <p>ISIN and trade date are not compared here: every pass only compares records that already
 * agree on them (hash key or fuzzy bucket).
 */
public enum Field {
    SIDE("side", 20, BreakType.SIDE_MISMATCH),
    QUANTITY("quantity", 20, BreakType.QUANTITY_MISMATCH),
    PRICE("price", 20, BreakType.PRICE_OUT_OF_TOLERANCE),
    COUNTERPARTY("counterparty", 20, BreakType.COUNTERPARTY_MISMATCH),
    SETTLE_DATE("settleDate", 15, BreakType.SETTLE_DATE_MISMATCH),
    COMMISSION("commission", 15, BreakType.COMMISSION_DIFFERENCE),
    /**
     * Usually a side effect of another field (a confirm's net is derived from its own quantity,
     * price and commission), so it costs little and only raises a break on its own. There's no
     * dedicated break type; an unexplained net difference means fees or charges differ, which is
     * closest to COMMISSION_DIFFERENCE.
     */
    NET_AMOUNT("netAmount", 5, BreakType.COMMISSION_DIFFERENCE);

    private final String fieldName;
    private final int penalty;
    private final BreakType breakType;

    Field(String fieldName, int penalty, BreakType breakType) {
        this.fieldName = fieldName;
        this.penalty = penalty;
        this.breakType = breakType;
    }

    public String fieldName() {
        return fieldName;
    }

    public int penalty() {
        return penalty;
    }

    public BreakType breakType() {
        return breakType;
    }
}
