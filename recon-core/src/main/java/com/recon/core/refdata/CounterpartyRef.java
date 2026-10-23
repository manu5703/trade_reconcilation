package com.recon.core.refdata;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the matcher needs to know about a counterparty.
 *
 * @param id            canonical ID used in internal bookings
 * @param aliases       other names that appear on confirms
 * @param commissionBps equity commission schedule in basis points of principal
 */
public record CounterpartyRef(String id, List<String> aliases, BigDecimal commissionBps) {

    public CounterpartyRef {
        aliases = List.copyOf(aliases);
    }
}
