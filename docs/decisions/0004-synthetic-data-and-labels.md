# 0004: How synthetic breaks, noise and labels are generated

**Status:** accepted

## The unit of truth is a *case*
A case is a group of records that belong together, plus what a perfect matcher should conclude
about them. Each case is one row of `labels.csv`:

| column | meaning |
|---|---|
| `expected` | `MATCH` or `BREAK` |
| `break_type`, `break_field` | the injected break and the field it changed (empty for clean cases) |
| `noise` | harmless noise on a clean case: `PRICE_WITHIN_TOLERANCE`, `NET_AMOUNT_ROUNDING`, `REF_FORMAT` |
| `trade_refs`, `broker_refs` | pipe-separated refs of the records in the case |

Case shapes: a single trade (1 trade, 1 confirm); a block (2–8 allocations, 1 confirm for the
total); `MISSING_CONFIRM` (1 trade, 0 confirms); `UNEXPECTED_CONFIRM` (0 trades, 1 confirm);
`DUPLICATE_CONFIRM` (1 trade, 2 confirms with the same economics and different refs).

## Exact counts, planned up front
`CasePlanner` fixes the shape of every case for a day before generating any content:
- Block allocations total `round(N × blockShare)` trades (or one less, when the last gap is a
  single trade).
- Breaks total exactly `round(N × breakRate)`, where **N is the number of internal trades**.
  They're split across types by largest-remainder allocation, so with 300 breaks and equal
  weights each type gets 33 or 34. A test checks this rather than leaning on luck.
- The plan is then shuffled with Fisher-Yates, so breaks land at random positions.
- `UNEXPECTED_CONFIRM` cases have no internal trade, so they're extra cases on top of the N trades.

## Simplifications (v1)
- **Breaks are injected on single trades only; blocks are always clean** (noise is still
  allowed). That keeps each label unambiguous. Breaks inside blocks are a planned extension.
- One break or one kind of noise per case, never both, and never two breaks.
- Weekends are skipped; holidays aren't modelled. Every trade is in one currency, and accrued
  interest on bonds is ignored.

## Economic conventions
- `net = principal + commission` for BUY, `principal − commission` for SELL. Bond principal is
  `face × price / 100`.
- Commission comes from each counterparty's schedule (`commission_bps` in `counterparties.csv`),
  rounded per allocation. Bonds trade net, with zero commission. The matcher compares the
  confirm's commission with the scheduled one, so it never has to back commission out of a
  rounded net amount.
- A confirm is internally consistent: its net amount is recalculated from its own (possibly
  broken) quantity, price, side and commission. So a quantity break also moves the net amount,
  and the matcher has to attribute the break to its root cause.
- Each counterparty sends its confirms from one source (BROKER, or CLEARING for clearing firms).
  A duplicate is a resend with a new broker reference.

## Noise is harmless by construction
Price noise moves the price by at most **half** of whichever band is tighter: the price
tolerance, or the price move that would push the net amount out of *its* tolerance (keeping a
cent back for rounding). For very large trades no such move exists, and the case gets rounding
noise instead. A test re-checks every clean case with an independent oracle (`CaseOracle`) that
normalizes refs and aliases and applies the configured tolerances.
