package com.recon.core;

import java.math.BigDecimal;

/** Argument checks shared by the domain records. Failures name the offending field. */
public final class Checks {

    private Checks() {
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public static String requiredText(String value, String field) {
        if (required(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static BigDecimal positive(BigDecimal value, String field) {
        if (required(value, field).signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive but was " + value.toPlainString());
        }
        return value;
    }

    public static BigDecimal nonNegative(BigDecimal value, String field) {
        if (required(value, field).signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative but was " + value.toPlainString());
        }
        return value;
    }
}
