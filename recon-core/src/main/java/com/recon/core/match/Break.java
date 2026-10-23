package com.recon.core.match;

import com.recon.core.model.BreakType;
import java.util.Comparator;

/**
 * A reconciliation break found by a run.
 *
 * @param internalRef the trade involved; null for UNEXPECTED_CONFIRM
 * @param externalRef the confirm involved; null for MISSING_CONFIRM
 * @param field       the field that differs, for field-level breaks; otherwise null
 */
public record Break(BreakType type, String internalRef, String externalRef, String field, String expected, String actual) {

    /** Canonical output order, so results never depend on input order. */
    static final Comparator<Break> ORDER = Comparator
            .comparing(Break::anchorRef)
            .thenComparing(Break::type)
            .thenComparing(b -> b.field() == null ? "" : b.field())
            .thenComparing(b -> b.externalRef() == null ? "" : b.externalRef());

    /** Identifies "the same break" across runs, for carry-over. */
    public record Key(BreakType type, String internalRef, String externalRef, String field) {
    }

    public Key key() {
        return new Key(type, internalRef, externalRef, field);
    }

    /** The trade ref if there is one, else the confirm ref. */
    public String anchorRef() {
        return internalRef != null ? internalRef : externalRef;
    }

    static Break missingConfirm(String internalRef) {
        return new Break(BreakType.MISSING_CONFIRM, internalRef, null, null, null, null);
    }

    static Break unexpectedConfirm(String externalRef) {
        return new Break(BreakType.UNEXPECTED_CONFIRM, null, externalRef, null, null, null);
    }

    /** {@code expected} names the confirm already matched; {@code actual} the one that repeats it. */
    static Break duplicateConfirm(String internalRef, String originalRef, String duplicateRef) {
        return new Break(BreakType.DUPLICATE_CONFIRM, internalRef, duplicateRef, null, originalRef, duplicateRef);
    }

    static Break fieldBreak(String internalRef, String externalRef, FieldDiff diff) {
        return new Break(diff.field().breakType(), internalRef, externalRef, diff.field().fieldName(), diff.expectedText(), diff.actualText());
    }
}
