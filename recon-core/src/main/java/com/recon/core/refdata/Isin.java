package com.recon.core.refdata;

import java.util.regex.Pattern;

/**
 * ISIN (ISO 6166) format and check-digit rules: 2-letter country code, 9 alphanumeric characters,
 * then a check digit.
 *
 * <p>The check digit is Luhn (mod 10) applied after expanding each letter to two digits
 * (A=10 ... Z=35). For example US037833100 expands to 3028037833100 and its check digit is 5.
 */
public final class Isin {

    private static final Pattern BODY = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}");
    private static final Pattern FULL = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private Isin() {
    }

    public static boolean isValid(String isin) {
        return isin != null
                && FULL.matcher(isin).matches()
                && checkDigit(isin.substring(0, 11)) == isin.charAt(11) - '0';
    }

    /** Appends the check digit to an 11-character ISIN body. */
    public static String withCheckDigit(String body) {
        return body + checkDigit(body);
    }

    static int checkDigit(String body) {
        if (body == null || !BODY.matcher(body).matches()) {
            throw new IllegalArgumentException("ISIN body must be 2 letters + 9 alphanumerics: " + body);
        }
        StringBuilder digits = new StringBuilder(22);
        for (char c : body.toCharArray()) {
            // Character.digit maps '0'-'9' to 0-9 and 'A'-'Z' to 10-35.
            digits.append(Character.digit(c, 36));
        }
        // Luhn: walking from the right, double every other digit starting with the rightmost,
        // because the check digit will occupy the (undoubled) last position.
        int sum = 0;
        boolean doubleIt = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
