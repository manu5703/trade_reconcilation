# 0003: The generator writes Parquet by converting its CSV with embedded DuckDB

**Status:** accepted

## Context
The Spark job reads Parquet. The usual Java writer, parquet-java's `ParquetWriter`, needs
`hadoop-common` on the classpath, which brings in dozens of transitive dependencies for a
command-line generator.

## Decision
The generator streams CSV (the format people can read), then runs one DuckDB statement per file:
`COPY (SELECT * FROM read_csv(..., auto_detect = false, columns = {...})) TO ... (FORMAT PARQUET)`.
The column types are given explicitly in `Column.java`, so decimals are always `DECIMAL(20,6)`
and dates are `DATE`, whatever the data looks like. Empty `block_ref` values become NULL.

## Consequences
- One dependency (`duckdb_jdbc`, which bundles a native library) instead of the Hadoop tree.
- CSV and Parquet always hold the same rows, because one is made from the other. A test checks
  row counts, column types, and that padded ISINs (REF_FORMAT noise) survive unchanged.
- Conversion runs after the CSV is complete, so it briefly needs disk space for both copies.
