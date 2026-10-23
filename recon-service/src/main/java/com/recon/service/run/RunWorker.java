package com.recon.service.run;

import com.recon.core.match.Break;
import com.recon.core.match.CarryOver;
import com.recon.core.match.CarryOver.OpenBreak;
import com.recon.core.match.MatchResult;
import com.recon.core.match.MatchRule;
import com.recon.core.match.Matcher;
import com.recon.core.model.BreakType;
import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.refdata.StaticData;
import com.recon.core.tolerance.ToleranceConfig;
import com.recon.service.metrics.ReconMetrics;
import com.recon.service.run.ReconDataRepository.CarriedBreak;
import com.recon.service.run.ReconDataRepository.NewBreak;
import com.recon.service.run.ReconDataRepository.OpenBreakRow;
import com.recon.service.run.ReconDataRepository.Records;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes one run: load the business date, match it with recon-core, carry over open breaks,
 * and write links, breaks and counts in a single transaction. A failure rolls all of that back and
 * marks the run FAILED in a second transaction, so a run never leaves half its results behind.
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final ReconRunRepository runs;
    private final ReconDataRepository data;
    private final ToleranceConfig tolerances;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ReconMetrics metrics;

    public RunWorker(ReconRunRepository runs, ReconDataRepository data, ToleranceConfig tolerances,
                     TransactionTemplate transaction, Clock clock, ReconMetrics metrics) {
        this.runs = runs;
        this.data = data;
        this.tolerances = tolerances;
        this.transaction = transaction;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** What a successful run hands to the metrics once its transaction has committed. */
    private record Outcome(ReconRun run, Map<BreakType, Long> breaksByType) {
    }

    public void process(long runId) {
        try {
            Outcome outcome = transaction.execute(status -> reconcile(runId));
            metrics.runCompleted(outcome.run(), outcome.breaksByType());
            log.info("Run {} for {} completed in {} ms", runId, outcome.run().getBusinessDate(), outcome.run().getDurationMs());
        } catch (RuntimeException e) {
            log.error("Run {} failed", runId, e);
            transaction.executeWithoutResult(status ->
                    runs.findById(runId).ifPresent(r -> r.fail(describe(e), clock.instant())));
            metrics.runFailed();
        }
    }

    private Outcome reconcile(long runId) {
        ReconRun run = runs.findById(runId).orElseThrow();
        Instant now = clock.instant();
        StaticData staticData = data.staticData();
        Records records = data.recordsFor(run.getBusinessDate());

        MatchResult result = Matcher.full(staticData, tolerances).match(records.trades(), records.confirms());

        List<OpenBreakRow> open = data.openBreaksFor(run.getBusinessDate());
        Map<Break.Key, Long> openIds = ReconDataRepository.idsByKey(open);
        CarryOver.Result carry = CarryOver.apply(result,
                open.stream().map(o -> new OpenBreak(o.key(), o.openedAt())).toList(), now);

        Counterparties counterparties = new Counterparties(staticData, records);
        List<NewBreak> newBreaks = new ArrayList<>();
        List<CarriedBreak> carried = new ArrayList<>();
        for (CarryOver.DatedBreak dated : carry.breaks()) {
            Break brk = dated.brk();
            String counterparty = counterparties.of(brk);
            if (dated.carried()) {
                carried.add(new CarriedBreak(openIds.get(brk.key()), brk, counterparty));
            } else {
                newBreaks.add(new NewBreak(brk, idOrNull(records.tradeIds(), brk.internalRef()),
                        idOrNull(records.confirmIds(), brk.externalRef()), counterparty, dated.openedAt()));
            }
        }
        List<Long> resolvedIds = carry.autoResolved().stream().map(b -> openIds.get(b.key())).toList();

        data.insertLinks(runId, result.links(), records.tradeIds(), records.confirmIds());
        data.insertBreaks(runId, run.getBusinessDate(), newBreaks);
        data.updateCarried(runId, carried);
        data.resolveAutomatically(resolvedIds, now, "Auto-resolved by run " + runId + ": the break no longer occurs");

        run.complete(counts(records, result, newBreaks.size(), carried.size(), resolvedIds.size()), clock.instant());
        return new Outcome(run, result.breaks().stream().collect(Collectors.groupingBy(Break::type, Collectors.counting())));
    }

    private static ReconRun.Counts counts(Records records, MatchResult result, int newBreaks, int carried, int resolved) {
        Set<String> matchedTrades = new HashSet<>();
        result.matches().forEach(m -> matchedTrades.addAll(m.internalRefs()));
        Set<String> tradesInBreaks = result.breaks().stream().map(Break::internalRef).filter(ref -> ref != null).collect(Collectors.toSet());
        int stp = (int) matchedTrades.stream().filter(ref -> !tradesInBreaks.contains(ref)).count();
        Map<MatchRule, Integer> byRule = result.matchesByRule();
        return new ReconRun.Counts(records.trades().size(), records.confirms().size(), matchedTrades.size(), stp,
                byRule.getOrDefault(MatchRule.EXACT, 0), byRule.getOrDefault(MatchRule.BLOCK, 0), byRule.getOrDefault(MatchRule.FUZZY, 0),
                result.breaks().size(), newBreaks, carried, resolved);
    }

    private static Long idOrNull(Map<String, Long> ids, String ref) {
        return ref == null ? null : ids.get(ref);
    }

    private static String describe(RuntimeException e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() > 1_000 ? message.substring(0, 1_000) : message;
    }

    /** The canonical counterparty of a break: from its trade if it has one, else from its confirm. */
    private static final class Counterparties {
        private final StaticData staticData;
        private final Map<String, InternalTrade> trades;
        private final Map<String, ExternalConfirm> confirms;

        Counterparties(StaticData staticData, Records records) {
            this.staticData = staticData;
            this.trades = records.trades().stream().collect(Collectors.toMap(InternalTrade::tradeRef, Function.identity()));
            this.confirms = records.confirms().stream().collect(Collectors.toMap(ExternalConfirm::brokerRef, Function.identity()));
        }

        String of(Break brk) {
            String raw = brk.internalRef() != null
                    ? trades.get(brk.internalRef()).counterparty()
                    : confirms.get(brk.externalRef()).counterparty();
            return staticData.canonicalCounterparty(StaticData.normalizeCode(raw));
        }
    }
}
