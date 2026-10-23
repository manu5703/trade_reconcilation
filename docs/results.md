# Results

Measured on 2026-10-23. Raw outputs are in [`docs/results/`](results/); each section below says
how to reproduce its numbers.

**Machine:** Apple M5 (10 cores), 16 GB RAM, macOS 26.7, Temurin JDK 21.0.13, PostgreSQL 16
(Docker Desktop VM: 10 CPUs, 7.7 GB). The machine was shared with other work during the runs
(load average 5–12), so timings carry some noise; JMH's error bars show how much.

## Summary

All accuracy figures are against the generator's `labels.csv`, seed 42, default settings (3%
breaks spread evenly over 9 types, 10% harmless noise, 15% block allocations).

| | Full matcher | Exact-only baseline |
|---|---|---|
| **Precision / recall, by break type** (1M trades) | | |
| MISSING_CONFIRM | 99.9% / 100.0% | 1.9% / 100.0% |
| UNEXPECTED_CONFIRM | 100.0% / 99.9% | 5.9% / 100.0% |
| QUANTITY_MISMATCH | 100.0% / 100.0% | — / 0.0% |
| PRICE_OUT_OF_TOLERANCE | 100.0% / 100.0% | — / 0.0% |
| SETTLE_DATE_MISMATCH | 100.0% / 100.0% | — / 0.0% |
| SIDE_MISMATCH | 100.0% / 99.9% | — / 0.0% |
| COUNTERPARTY_MISMATCH | 100.0% / 100.0% | — / 0.0% |
| COMMISSION_DIFFERENCE | 100.0% / 100.0% | — / 0.0% |
| DUPLICATE_CONFIRM | 99.9% / 100.0% | — / 0.0% |
| **All breaks** (1M trades / 10M trades) | 100.0% / 100.0% · 99.57% / 99.60% | 2.9% / 22.2% |
| **False-break rate on noise rows** (1M) | 0.00% (0 of 85,420) | 3.50% |
| **STP rate** (1M; a perfect matcher gets 97.33%) | 97.33% | 82.67% |
| **Matcher throughput**, one JVM (JMH, 1M trades + 883k confirms) | ≈527k rows/s (3.57 s ± 0.53) | ≈551k rows/s (3.42 s ± 1.59) |
| **10M-trade batch**, Spark local mode on 10 cores (read Parquet → match → write Parquet) | 48–52 s, ≈360–390k rows/s | — |
| **API latency**, `GET /api/breaks`, 25 concurrent users, 30k-break queue | p50 8.0 ms · p99 28.8 ms · 2,347 req/s · 0 errors | — |

"—" = the baseline never predicts that type, so precision is undefined.

## Accuracy

`./gradlew :recon-datagen:score --args="--data <dir>"` runs both matchers and prints the table
([1M output](results/score-1m-200-instruments.txt)).

- **The baseline misses most breaks.** It can only say "missing" or "unexpected": every field
  break, duplicate and block shows up as a missing trade plus an unexpected confirm. That's
  where its 2–6% precision comes from. It also raises false breaks on 3.5% of noise rows,
  namely the noisy blocks.
- **The full matcher's remaining errors are ambiguity, not bugs.** At 1M trades, 3 of 853,365
  clean cases get a false break. `--show-errors` lists them, and each one is a pair of bookings
  that are identical within tolerance.
- **At 10M trades accuracy drops to 99.6%** ([Spark output](results/spark-10m.txt)). The cause
  is density, not volume: 1M trades on 20 instruments (the same trades per instrument) gives the
  same 99.5% / 99.6% ([output](results/score-1m-20-instruments.txt)). See
  [decision 0008](decisions/0008-scaling.md#4-finding-accuracy-depends-on-book-density-not-volume).
- **Spark and the single-JVM matcher agree exactly.** `ReconJobTest` compares every link, every
  break and the label evaluation.

## Matcher throughput (JMH)

`./gradlew :bench:jmhJar && java -jar bench/build/libs/bench-jmh.jar -jvmArgsAppend "-Xms6g -Xmx6g -Drecon.rootDir=$PWD"`
([output](results/jmh-matcher.txt), [JSON](results/jmh-matcher.json)). Average time per full match of one
business date: 3 warm-up and 5 measured iterations, 1 fork, 6 GB heap.

| Trades (+ confirms) | Full matcher | Exact-only baseline |
|---|---|---|
| 100k (+88k) | 266 ± 87 ms (≈709k rows/s) | 313 ± 32 ms (≈602k rows/s) |
| 1M (+883k) | 3,571 ± 534 ms (≈527k rows/s) | 3,417 ± 1,593 ms (≈551k rows/s) |

The extra passes cost almost nothing: normalizing, sorting and hashing every record dominates
both matchers. That only holds because the fuzzy pass uses **blocking**. Comparing every pair in
each (ISIN, date) bucket grows with the square of volume, and blocking finds exactly the same
pairs ([decision 0008](decisions/0008-scaling.md)).

## 10M-trade batch (Spark)

```
./gradlew :recon-datagen:run --args="--trades 10000000 --seed 42 --out build/data-10m"   # 35 s
./gradlew :recon-spark:run --args="--data build/data-10m --out build/spark-out-10m"
```

| Stage | Time |
|---|---|
| Read Parquet → group by (ISIN, date) → match → write links and breaks as Parquet | 48.4 s and 52.2 s (two runs) |
| Summary counts (by rule, by type, STP) | ≈120 s |
| Scoring against the 740 MB `labels.csv` (evaluation only, not part of a real run) | ≈147 s |

The batch produced 8,266,673 exact, 1,500,000 block and 199,888 fuzzy matches, 300,099 breaks and
97.33% STP. Both runs gave identical results. The summary steps re-read the cached result and run
`distinct`/`except` over 10M refs. They could be folded into the matching tasks, but they're
reporting, not reconciliation.

## API latency (k6)

The service ran in Docker Compose with a 1M-trade day imported (14.7 s via `COPY`) and
reconciled (40.8 s end to end; about 4 s of that is matching, the rest is reading 1.9M rows and
writing 1M links and 30,002 breaks in one transaction). k6 then ran 25 virtual users with no
think time for 40 s (after a 10 s ramp), each request a random realistic queue query: the default
view, a type, status, counterparty or age filter, a different sort, or a deep page.

```
docker run --rm -i --network trade_reconcilation_default -e BASE_URL=http://recon-service:8080 \
  -v "$PWD/bench/k6:/scripts" grafana/k6:1.1.0 run /scripts/breaks.js
```

| | Requests | Throughput | p50 | p90 | p95 | p99 | max | Errors |
|---|---|---|---|---|---|---|---|---|
| Before sort indexes | 3,438 | 63 req/s | 318 ms | 644 ms | 691 ms | 842 ms | 1,328 ms | 0 |
| **After (V2 migration)** | **129,072** | **2,347 req/s** | **8.0 ms** | 16.7 ms | 20.8 ms | **28.8 ms** | 57.9 ms | **0** |

The first run missed its p99 < 500 ms threshold. `EXPLAIN ANALYZE` showed why: without an index
matching the sort, PostgreSQL joined all 30k breaks to the trade and confirm tables before
keeping 25 rows (260 ms per page). The V2 migration adds a `(column, id)` index per sortable
column ([JSON](results/k6-breaks.json)). The run went over the Compose network: going through
Docker Desktop's host port forwarding instead dropped 0.03% of connections at this rate, which
measured the forwarder, not the service.
