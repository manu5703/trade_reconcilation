package com.recon.datagen.refdata;

import com.recon.core.model.AssetClass;
import com.recon.core.model.Source;
import com.recon.core.refdata.Isin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Generates instruments, counterparties and accounts. Counterparty names are fictional. */
public final class ReferenceDataGenerator {

    private static final String[] EQUITY_COUNTRIES = {"US", "US", "US", "GB", "DE", "FR", "JP", "CA"};
    // XS is the prefix for international (Eurobond) issues.
    private static final String[] BOND_COUNTRIES = {"US", "XS"};
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Real national codes (CUSIP, SEDOL, ...) are mostly digits; letters appear occasionally. */
    private static final double NSIN_LETTER_SHARE = 0.15;

    private static final String[] STEMS = {
        "Alder", "Birchwood", "Cobalt", "Driftwood", "Ember", "Fairhaven", "Granite", "Harbor",
        "Ironvale", "Juniper", "Kestrel", "Lakeshore", "Meridian", "Northgate", "Oakridge",
        "Pinecrest", "Quarry", "Redwood", "Summit", "Tidewater", "Upland", "Vantage", "Westbrook",
        "Yellowpine", "Zenith", "Ashford", "Bluestone", "Copperfield", "Dunmore", "Eastlake"
    };
    private static final String[] SUFFIXES = {"Securities LLC", "Capital Markets", "Brokerage Inc.", "Clearing Corp.", "& Co."};
    private static final String[] SUFFIX_ABBREVIATIONS = {"SEC", "CAP MKTS", "BRKG", "CLR", "AND CO"};
    private static final int CLEARING_SUFFIX = 3;

    /** Share of instruments that are equities; the rest are bonds. */
    private static final double EQUITY_SHARE = 0.75;

    private ReferenceDataGenerator() {
    }

    public static ReferenceData generate(RandomGenerator rng, int instruments, int counterparties, int accounts) {
        return new ReferenceData(
                instruments(rng, instruments),
                counterparties(rng, counterparties),
                accounts(accounts));
    }

    private static List<Instrument> instruments(RandomGenerator rng, int count) {
        int equities = (int) Math.round(count * EQUITY_SHARE);
        Set<String> seen = new LinkedHashSet<>();
        List<Instrument> result = new ArrayList<>(count);
        while (result.size() < count) {
            boolean equity = result.size() < equities;
            String isin = isin(rng, equity ? EQUITY_COUNTRIES : BOND_COUNTRIES);
            if (!seen.add(isin)) {
                continue;
            }
            // The random double only seeds a synthetic reference price; it never enters cash arithmetic.
            BigDecimal referencePrice = equity
                    ? BigDecimal.valueOf(5 + 795 * rng.nextDouble()).setScale(2, RoundingMode.HALF_EVEN)
                    : BigDecimal.valueOf(85 + 30 * rng.nextDouble()).setScale(3, RoundingMode.HALF_EVEN);
            result.add(new Instrument(isin, equity ? AssetClass.EQUITY : AssetClass.FIXED_INCOME, referencePrice));
        }
        return result;
    }

    private static String isin(RandomGenerator rng, String[] countries) {
        StringBuilder body = new StringBuilder(countries[rng.nextInt(countries.length)]);
        for (int i = 0; i < 9; i++) {
            body.append(rng.nextDouble() < NSIN_LETTER_SHARE
                    ? LETTERS.charAt(rng.nextInt(LETTERS.length()))
                    : (char) ('0' + rng.nextInt(10)));
        }
        return Isin.withCheckDigit(body.toString());
    }

    private static List<Counterparty> counterparties(RandomGenerator rng, int count) {
        List<Counterparty> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Past 30 counterparties the stems repeat with a number, keeping names unique.
            String stem = STEMS[i % STEMS.length] + (i < STEMS.length ? "" : " " + (i / STEMS.length + 1));
            int suffix = rng.nextInt(SUFFIXES.length);
            String name = stem + " " + SUFFIXES[suffix];
            String upper = stem.toUpperCase(Locale.ROOT);
            List<String> aliases = List.of(
                    name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").replaceAll(" +", " ").trim(),
                    upper + " " + SUFFIX_ABBREVIATIONS[suffix],
                    name);
            Source source = suffix == CLEARING_SUFFIX ? Source.CLEARING : Source.BROKER;
            // 0.5 to 3.0 bps in steps of 0.1.
            BigDecimal commissionBps = BigDecimal.valueOf(5 + rng.nextInt(26), 1);
            result.add(new Counterparty("CP%03d".formatted(i + 1), name, aliases, source, commissionBps,
                    upper.replace(" ", "").substring(0, 3)));
        }
        return result;
    }

    private static List<String> accounts(int count) {
        List<String> result = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            result.add("ACC%05d".formatted(i));
        }
        return result;
    }
}
