# 0001: BigDecimal and NUMERIC(20,6) for money, prices and quantities

**Status:** accepted

## Context
Matching compares prices and cash amounts against tolerances like "within 0.005" or "within
2 bps". Binary floating point can't represent most decimal fractions exactly (`0.1 + 0.2 !=
0.3`), so a `double` comparison can land on the wrong side of a tolerance edge, and summing
block allocations piles up error.

## Decision
- Every price, quantity, commission and cash amount is a `BigDecimal` in Java and a
  `NUMERIC(20,6)` / `DECIMAL(20,6)` in Postgres and Parquet.
- Comparisons use `compareTo`, not `equals`, because `equals` also compares scale (`1.0` vs `1.00`).
- Cash is rounded to cents with `HALF_EVEN` (banker's rounding). Weighted average prices are
  divided to 6 decimals with `HALF_EVEN`. Every rounding call names its mode.
- Tolerance bands divide by 10,000 with `movePointLeft(4)`, which is exact.
- Rates in the generator config (break rate, noise rate) are probabilities, not money, so they stay `double`.

## Consequences
Arithmetic is slower than with primitives. Stage 5 benchmarks will show whether that matters;
the matcher's hash join keeps the number of comparisons at O(n), so it shouldn't.
