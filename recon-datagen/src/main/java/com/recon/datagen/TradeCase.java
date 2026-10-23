package com.recon.datagen;

import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import java.time.LocalDate;
import java.util.List;

/**
 * One unit of ground truth: the internal trades and external confirms that belong together, and
 * what a perfect matcher should conclude about them. Becomes one row of labels.csv.
 *
 * <p>Shapes: a single trade has 1 trade; a block has 2-8 allocations sharing {@code blockRef}
 * and one confirm for the total; MISSING_CONFIRM has no confirm; UNEXPECTED_CONFIRM has no
 * trade; DUPLICATE_CONFIRM has two confirms.
 *
 * @param breakType  the injected break, or null when the case should match cleanly
 * @param breakField the field that was altered, for field-level breaks; otherwise null
 * @param noise      harmless noise applied to a clean case, or null
 */
public record TradeCase(
        String caseId,
        LocalDate tradeDate,
        BreakType breakType,
        String breakField,
        NoiseType noise,
        String blockRef,
        List<InternalTrade> trades,
        List<ExternalConfirm> confirms) {

    public TradeCase {
        trades = List.copyOf(trades);
        confirms = List.copyOf(confirms);
    }

    public boolean isBreak() {
        return breakType != null;
    }
}
