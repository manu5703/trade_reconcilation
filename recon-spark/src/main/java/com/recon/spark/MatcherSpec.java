package com.recon.spark;

import com.recon.core.match.Matcher;
import com.recon.core.model.AssetClass;
import com.recon.core.refdata.CounterpartyRef;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Everything an executor needs to build a {@link Matcher}, as plain serializable data. Spark
 * ships this to executors once (a broadcast); each executor builds its Matcher from it, so
 * recon-core's classes never need to be serializable or know about Spark.
 */
public final class MatcherSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** One counterparty, with its aliases and commission schedule as plain values. */
    public record Counterparty(String id, ArrayList<String> aliases, String commissionBps) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private final HashMap<String, String> assetClassByIsin;
    private final ArrayList<Counterparty> counterparties;
    private final Properties tolerances;
    private transient Matcher matcher;

    public MatcherSpec(Map<String, String> assetClassByIsin, List<Counterparty> counterparties, Properties tolerances) {
        this.assetClassByIsin = new HashMap<>(assetClassByIsin);
        this.counterparties = new ArrayList<>(counterparties);
        this.tolerances = tolerances;
        // Build once on the driver so bad reference data or tolerances fail before any task runs.
        matcher();
    }

    /** The full matcher, built on first use in each JVM and then reused for every group. */
    public synchronized Matcher matcher() {
        if (matcher == null) {
            Map<String, AssetClass> instruments = new HashMap<>();
            assetClassByIsin.forEach((isin, assetClass) -> instruments.put(isin, AssetClass.valueOf(assetClass)));
            List<CounterpartyRef> refs = counterparties.stream()
                    .map(c -> new CounterpartyRef(c.id(), c.aliases(), new BigDecimal(c.commissionBps())))
                    .toList();
            matcher = Matcher.full(new StaticData(instruments, refs), ToleranceConfig.fromProperties(tolerances));
        }
        return matcher;
    }
}
