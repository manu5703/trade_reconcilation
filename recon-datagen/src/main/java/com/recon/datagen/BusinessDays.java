package com.recon.datagen;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Weekday arithmetic. Holidays are ignored (a known limitation of the synthetic calendar). */
final class BusinessDays {

    private BusinessDays() {
    }

    static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    static LocalDate onOrAfter(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    static LocalDate add(LocalDate date, int businessDays) {
        LocalDate d = date;
        int added = 0;
        while (added < businessDays) {
            d = d.plusDays(1);
            if (isBusinessDay(d)) {
                added++;
            }
        }
        return d;
    }
}
