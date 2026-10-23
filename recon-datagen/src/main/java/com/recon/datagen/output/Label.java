package com.recon.datagen.output;

import com.recon.core.model.BreakType;
import com.recon.datagen.NoiseType;
import java.util.List;

/** One row of labels.csv: the ground truth for one case. */
public record Label(
        String caseId,
        BreakType breakType,
        String breakField,
        NoiseType noise,
        String blockRef,
        List<String> tradeRefs,
        List<String> brokerRefs) {

    public Label {
        tradeRefs = List.copyOf(tradeRefs);
        brokerRefs = List.copyOf(brokerRefs);
    }

    public boolean isBreak() {
        return breakType != null;
    }

    /** The trade ref if the case has one, else the confirm ref; the same rule as {@code Break.anchorRef()}. */
    public String anchorRef() {
        return tradeRefs.isEmpty() ? brokerRefs.getFirst() : tradeRefs.getFirst();
    }
}
