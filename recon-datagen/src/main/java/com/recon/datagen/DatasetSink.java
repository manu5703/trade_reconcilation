package com.recon.datagen;

import com.recon.datagen.refdata.ReferenceData;
import java.io.IOException;

/** Receives generated data. Cases arrive one at a time so large runs can stream to disk. */
public interface DatasetSink extends AutoCloseable {

    void referenceData(ReferenceData referenceData) throws IOException;

    void accept(TradeCase tradeCase) throws IOException;

    @Override
    default void close() throws IOException {
    }
}
