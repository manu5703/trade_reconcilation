package com.recon.core.model;

/**
 * Classification of a reconciliation break.
 *
 * <p>An enum rather than a sealed interface: the type itself carries no data. The field name and
 * the expected/actual values belong to the break record that references the type.
 */
public enum BreakType {
    /** An internal trade with no matching confirm. */
    MISSING_CONFIRM,
    /** A confirm with no matching internal trade. */
    UNEXPECTED_CONFIRM,
    QUANTITY_MISMATCH,
    PRICE_OUT_OF_TOLERANCE,
    SETTLE_DATE_MISMATCH,
    SIDE_MISMATCH,
    COUNTERPARTY_MISMATCH,
    /** Confirmed commission differs from the counterparty's commission schedule. */
    COMMISSION_DIFFERENCE,
    /** More than one confirm for the same trade. */
    DUPLICATE_CONFIRM
}
