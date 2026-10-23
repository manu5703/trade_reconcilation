package com.recon.datagen.output;

import java.util.List;

/** A CSV column and the SQL type it has in Parquet (and, later, in Postgres). */
record Column(String name, String sqlType) {

    private static final String DECIMAL = "DECIMAL(20,6)";

    static final List<Column> INTERNAL_TRADE = List.of(
            new Column("trade_ref", "VARCHAR"),
            new Column("block_ref", "VARCHAR"),
            new Column("account", "VARCHAR"),
            new Column("isin", "VARCHAR"),
            new Column("side", "VARCHAR"),
            new Column("quantity", DECIMAL),
            new Column("price", DECIMAL),
            new Column("trade_date", "DATE"),
            new Column("settle_date", "DATE"),
            new Column("counterparty", "VARCHAR"),
            new Column("net_amount", DECIMAL));

    static final List<Column> EXTERNAL_CONFIRM = List.of(
            new Column("broker_ref", "VARCHAR"),
            new Column("source", "VARCHAR"),
            new Column("isin", "VARCHAR"),
            new Column("side", "VARCHAR"),
            new Column("quantity", DECIMAL),
            new Column("price", DECIMAL),
            new Column("commission", DECIMAL),
            new Column("trade_date", "DATE"),
            new Column("settle_date", "DATE"),
            new Column("counterparty", "VARCHAR"),
            new Column("net_amount", DECIMAL));

    static String[] names(List<Column> columns) {
        return columns.stream().map(Column::name).toArray(String[]::new);
    }
}
