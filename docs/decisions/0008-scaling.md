# 0008: Scaling to 10M trades

**Status:** accepted

## 1. Blocking in the fuzzy pass (exact, not approximate)
The fuzzy pass compared every unmatched trade with every unmatched confirm in its (isin,
tradeDate) bucket. With a fixed number of instruments, buckets grow with volume, so the cost
grows with its square: about 4.5M comparisons at 1M rows and 450M at 10M.

An accepted pair (score > 70) can differ in **at most one** of side, counterparty and quantity,
because any two of them cost at least 40 points and cap the score at 60. So it always agrees on
at least two of the three. Indexing confirms by (side, counterparty), (side, quantity) and
(counterparty, quantity) therefore finds every pair the full comparison would accept, while
scoring only a handful per trade.

This is guarded three ways:
- `FuzzyPass` refuses a threshold or penalties for which the guarantee no longer holds.
- `FuzzyBlockingTest` checks it exhaustively over all 2^7 combinations of differing fields.
- A jqwik property compares the blocked pass with the old all-pairs pass on 500 random,
  collision-heavy books and requires identical results.

## 2. Spark groups by (normalized ISIN, trade date) and runs the unchanged matcher
Every recon-core pass only compares records that share both values: the exact and duplicate
keys contain them, block allocations must agree on them, and fuzzy blocking happens inside them.
Groups never compete for a record, so matching each group independently and taking the union
is **identical** to one `Matcher.full` over all the data. `ReconJobTest` checks that equality on
links, breaks and the label evaluation. The Spark job is a `cogroup` plus a call into
recon-core; no matching logic is duplicated.

Each group is matched inside one task, holding only that group in memory. As plain Java objects,
10M trades plus 8.8M confirms would need far more than this machine's 16 GB, which is why the
10M figure comes from Spark rather than the single-JVM CLI.

Reference data and tolerances reach executors as a small broadcast `MatcherSpec` of plain
serializable values. recon-core stays free of Spark and of `Serializable`. Spark's bundled Kryo
can't serialize Java records, so the broadcast uses Java serialization.

## 3. Break-queue sort indexes (V2 migration)
The first k6 run gave p99 842 ms at 63 req/s. An unfiltered page of the queue took 260 ms in
PostgreSQL: with no index matching `ORDER BY opened_at, id`, the planner hash-joined all 30k
breaks to 1M trades and 883k confirms before sorting and keeping 25 rows. One `(column, id)`
index per sortable column, with the `id` tie-breaker sorted in the same direction as the column,
turns a page into an ordered index scan plus 25 key lookups (8–18 ms). Re-run: p99 28.8 ms at
2,347 req/s.

## 4. Finding: accuracy depends on book density, not volume
At 10M trades the matcher scores 99.6% precision and recall, against about 100% at 1M. A
controlled run shows why: **1M trades on 20 instruments** (the same trades per instrument as
10M on 200) also gives 99.5% / 99.6%. With about 50k trades per instrument per day, unrelated
bookings with the same ISIN, side, counterparty and lot size, and prices within 2 bps, become
common. They're genuinely indistinguishable on the compared fields, so a missing or broken
confirm's partner is sometimes taken by a near-identical neighbour. Real books spread volume
over thousands of instruments, so 10M-on-200 is a stress test, not a forecast. Possible
remedies: optimal (Hungarian) assignment per block instead of greedy; matching on additional
fields when brokers provide them (order IDs, execution times); or, in production, a per-account
allocation feed.
