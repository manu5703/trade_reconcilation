package com.recon.datagen.refdata;

import com.recon.core.refdata.CounterpartyRef;
import com.recon.core.refdata.StaticData;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ReferenceData(List<Instrument> instruments, List<Counterparty> counterparties, List<String> accounts) {

    public ReferenceData {
        instruments = List.copyOf(instruments);
        counterparties = List.copyOf(counterparties);
        accounts = List.copyOf(accounts);
    }

    public Map<String, Instrument> instrumentsByIsin() {
        return instruments.stream().collect(Collectors.toMap(Instrument::isin, Function.identity()));
    }

    public Map<String, Counterparty> counterpartiesById() {
        return counterparties.stream().collect(Collectors.toMap(Counterparty::id, Function.identity()));
    }

    /** The subset the matcher needs, as it would be loaded from instruments.csv and counterparties.csv. */
    public StaticData toStaticData() {
        return new StaticData(
                instruments.stream().collect(Collectors.toMap(Instrument::isin, Instrument::assetClass)),
                counterparties.stream().map(c -> new CounterpartyRef(c.id(), c.aliases(), c.commissionBps())).toList());
    }
}
