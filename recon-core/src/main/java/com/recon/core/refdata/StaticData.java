package com.recon.core.refdata;

import com.recon.core.model.AssetClass;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reference ("static") data used by the matcher: each instrument's asset class, plus counterparty
 * aliases and commission schedules. Lookups take codes that are already normalized (trimmed and
 * upper case).
 */
public final class StaticData {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final Map<String, AssetClass> assetClassByIsin;
    private final Map<String, String> counterpartyIdByName = new HashMap<>();
    private final Map<String, BigDecimal> commissionBpsById = new HashMap<>();

    public StaticData(Map<String, AssetClass> assetClassByIsin, Collection<CounterpartyRef> counterparties) {
        this.assetClassByIsin = new HashMap<>();
        assetClassByIsin.forEach((isin, assetClass) -> this.assetClassByIsin.put(normalizeCode(isin), assetClass));
        for (CounterpartyRef counterparty : counterparties) {
            String id = normalizeCode(counterparty.id());
            register(id, id);
            counterparty.aliases().forEach(alias -> register(normalizeCode(alias), id));
            commissionBpsById.put(id, counterparty.commissionBps());
        }
    }

    /** Trims, upper-cases and collapses runs of spaces, so " us0378331005" and "US0378331005" compare equal. */
    public static String normalizeCode(String raw) {
        // Precompiled: String.replaceAll would compile the regex on every one of millions of calls.
        return WHITESPACE_RUN.matcher(raw.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);
    }

    public Optional<AssetClass> assetClass(String isin) {
        return Optional.ofNullable(assetClassByIsin.get(isin));
    }

    /** The canonical counterparty ID for a name or alias. An unknown name is returned unchanged. */
    public String canonicalCounterparty(String name) {
        return counterpartyIdByName.getOrDefault(name, name);
    }

    public Optional<BigDecimal> commissionBps(String counterpartyId) {
        return Optional.ofNullable(commissionBpsById.get(counterpartyId));
    }

    private void register(String name, String id) {
        String previous = counterpartyIdByName.putIfAbsent(name, id);
        if (previous != null && !previous.equals(id)) {
            throw new IllegalArgumentException("Counterparty alias '" + name + "' maps to both " + previous + " and " + id);
        }
    }
}
