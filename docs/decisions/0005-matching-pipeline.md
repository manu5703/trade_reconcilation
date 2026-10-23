# 0005: Matching pipeline design, and where it departs from the original spec

**Status:** accepted

## Pipeline
`Matcher.full`: normalize → **block → exact** → **duplicates** → fuzzy → leftovers. Carry-over
(`CarryOver.apply`) runs on the result, because it needs the breaks still open from earlier runs.
Each pass sees only the records earlier passes left unmatched. `MatchState.addMatch` refuses to
match a record twice, so the "no record in two matches" invariant is enforced where it could
break, not just tested.

## Departures from the spec, and why

**1. The exact pass also requires settle date and commission to agree.** The spec's exact pass
checks the key plus price and net amount. A pair with a settle-date or commission break passes
that check, so it would be matched as exact and the break would never be reported. Now
"exact" means every compared field agrees.

**2. Fuzzy buckets are (isin, tradeDate), and side is a scored field.** Bucketing on
(isin, side, tradeDate) puts a side-flipped confirm in a different bucket from its trade, so
SIDE_MISMATCH could never be detected.

**3. Duplicate detection runs before fuzzy, not in leftovers.** A stress run turned this up
(50k trades, 30% breaks, seed 7). A resent confirm was claimed by the fuzzy pass for an
unrelated trade with the same bond, counterparty, side and quantity (score 75, price differed).
The result was a false PRICE_OUT_OF_TOLERANCE, a missed DUPLICATE_CONFIRM and a missed
MISSING_CONFIRM. Running duplicates first fixed it: an exact repeat of a confirm that's already
matched is far more likely a resend than a price break on another trade.

**4. Blocks are matched before exact pairs.** At 1M trades, an allocation sometimes
exact-matched an unrelated confirm with the same (isin, side, date, counterparty, quantity) and
a price within 2 bps. The rest of the block then couldn't reconcile, and each remaining
allocation showed up as a false MISSING_CONFIRM (76 of 104 false MISSINGs in that run). A
single trade's confirm practically never equals a block's summed quantity, so going first
costs nothing.

**5. Within an exact-key bucket the closest pair wins, not the first ref.** Taking the first
candidate let a trade whose own confirm was missing take a near-identical neighbour's confirm.
Pairs in a bucket are now sorted by price gap, then net gap. Buckets share no records, so each
is solved on its own and the pass stays O(n).

Effect on the 1M-trade default dataset: false breaks on clean cases fell from 45 to 3, and
correct pairing on clean cases rose from 99.99% to 100.00%.

**6. Carry-over resolves superseded breaks, not only matched ones.** An open break that didn't
recur is auto-resolved whenever its records were in the run, including when a different break
now describes them (MISSING_CONFIRM on Monday, QUANTITY_MISMATCH on Tuesday once the confirm
arrives). Otherwise the stale Monday break would stay open next to the new one forever.

## Fuzzy scoring
- Score = 100 − penalties (side 20, quantity 20, price 20, counterparty 20, settle date 15,
  commission 15, net amount 5). Accept above 70. Integer points avoid floating-point ties.
- One wrong field, plus the net amount it drags along, scores at least 75 (accepted). Two
  wrong fields score at most 65 (rejected); those cases end up as MISSING + UNEXPECTED, which is
  the safer outcome.
- Net amount on its own is reported as COMMISSION_DIFFERENCE with field `netAmount`. There's no
  separate net-amount type, and an unexplained net difference means fees or charges differ.

## Known limitation: greedy assignment
Greedy best-first can choose a locally best pair that blocks a better overall assignment.
Seen in the same stress run: trade A ↔ confirm X scores 80 and is taken first, but X belonged
to trade B, and A's own confirm Y (score 75) is then left to become UNEXPECTED. The optimal
assignment (A↔Y 75 + B↔X 80) beats greedy (80 + nothing). It happened 1 time in 15,000 breaks
at a 30% break rate, and never at the default 3%. The fix is optimal assignment per bucket
(Hungarian algorithm, O(k³) on small buckets).

## Baseline
`Matcher.baseline` = normalize + exact pass, with everything left over reported as MISSING or
UNEXPECTED. Normalization is included so the comparison isolates what the block, duplicate and
fuzzy passes add, rather than what trimming whitespace adds.

## Scoring (`ScoreCli`)
A predicted break is correct when its (type, anchor ref) equals a labelled break's; the anchor
is the trade ref, or the confirm ref when there's no trade. Precision and recall are per type.
A clean case has a false break if any predicted break touches any of its records. STP rate =
internal trades matched with no break ÷ all internal trades.
