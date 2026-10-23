package com.recon.datagen.refdata;

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
}
