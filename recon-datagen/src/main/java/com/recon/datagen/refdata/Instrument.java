package com.recon.datagen.refdata;

import com.recon.core.model.AssetClass;
import java.math.BigDecimal;

/** A tradable security. Trades are priced around {@code referencePrice}. */
public record Instrument(String isin, AssetClass assetClass, BigDecimal referencePrice) {
}
