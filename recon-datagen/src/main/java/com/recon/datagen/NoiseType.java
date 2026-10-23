package com.recon.datagen;

/**
 * Differences between a booking and its confirm that a correct matcher must NOT report as breaks.
 * Each one exercises a different part of the matcher: tolerances, net-amount rounding and the
 * normalizer.
 */
public enum NoiseType {
    /** Confirm price differs from the booking by less than half the configured price tolerance. */
    PRICE_WITHIN_TOLERANCE,
    /** Confirm net amount rounded differently (truncated to cents, or reported to 4 decimals). */
    NET_AMOUNT_ROUNDING,
    /** Confirm ISIN in lower case or padded with spaces, and counterparty given by an alias. */
    REF_FORMAT
}
