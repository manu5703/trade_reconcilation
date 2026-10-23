package com.recon.datagen;

import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import java.util.List;

/** Exposes the package-private {@link TestSupport} to tests in other packages. */
public final class TestSupportAccess {

    private TestSupportAccess() {
    }

    public record Generated(List<TradeCase> cases, StaticData staticData, ToleranceConfig tolerances) {
    }

    public static Generated generate(DatagenConfig config) {
        TestSupport.Dataset dataset = TestSupport.generate(config);
        return new Generated(dataset.cases(), dataset.referenceData().toStaticData(), TestSupport.tolerances());
    }

    public static String tolerancesFile() {
        return TestSupport.tolerancesFile().toString();
    }
}
