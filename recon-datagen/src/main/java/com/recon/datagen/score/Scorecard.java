package com.recon.datagen.score;

import com.recon.core.match.Break;
import com.recon.core.match.Match;
import com.recon.core.match.MatchResult;
import com.recon.core.model.BreakType;
import com.recon.datagen.output.Label;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores one matcher run against labels.csv.
 *
 * <p>A predicted break counts as correct when its type and anchor ref (trade ref, or confirm
 * ref if there's no trade) equal a labelled break's. Precision = correct / predicted and
 * recall = correct / labelled, per break type.
 *
 * <p>A clean case has a <em>false break</em> if any predicted break touches any of its records.
 * The <em>STP rate</em> (straight-through processing) is the share of internal trades that were
 * matched with no break at all, so nobody had to look at them.
 *
 * @param falsePositives predicted breaks with no matching label, as "TYPE anchorRef"
 * @param falseNegatives labelled breaks the matcher missed, as "TYPE anchorRef"
 */
public record Scorecard(
        Map<BreakType, TypeScore> byType,
        int noiseCases,
        int noiseCasesWithFalseBreaks,
        int cleanCases,
        int cleanCasesWithFalseBreaks,
        int cleanCasesCorrectlyPaired,
        int internalTrades,
        int straightThroughTrades,
        int idealStraightThroughTrades,
        List<String> falsePositives,
        List<String> falseNegatives) {

    public Scorecard {
        falsePositives = List.copyOf(falsePositives);
        falseNegatives = List.copyOf(falseNegatives);
    }

    public record TypeScore(int labelled, int predicted, int correct) {

        /** NaN when nothing was predicted. */
        public double precision() {
            return predicted == 0 ? Double.NaN : (double) correct / predicted;
        }

        /** NaN when nothing was labelled. */
        public double recall() {
            return labelled == 0 ? Double.NaN : (double) correct / labelled;
        }
    }

    private record Key(BreakType type, String anchorRef) {
        @Override
        public String toString() {
            return type + " " + anchorRef;
        }
    }

    public static Scorecard score(List<Label> labels, MatchResult result) {
        Set<Key> truth = new HashSet<>();
        Map<String, Label> caseByRef = new HashMap<>();
        int internalTrades = 0;
        for (Label label : labels) {
            if (label.isBreak()) {
                truth.add(new Key(label.breakType(), label.anchorRef()));
            }
            label.tradeRefs().forEach(ref -> caseByRef.put(ref, label));
            label.brokerRefs().forEach(ref -> caseByRef.put(ref, label));
            internalTrades += label.tradeRefs().size();
        }

        // A fuzzy pair with two wrong fields yields two breaks; each type is scored separately.
        Set<Key> predicted = new HashSet<>();
        Set<String> casesWithBreaks = new HashSet<>();
        Set<String> refsInBreaks = new HashSet<>();
        for (Break b : result.breaks()) {
            predicted.add(new Key(b.type(), b.anchorRef()));
            for (String ref : new String[] {b.internalRef(), b.externalRef()}) {
                if (ref != null) {
                    refsInBreaks.add(ref);
                    Label label = caseByRef.get(ref);
                    if (label != null) {
                        casesWithBreaks.add(label.caseId());
                    }
                }
            }
        }

        Map<BreakType, TypeScore> byType = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            int labelled = (int) truth.stream().filter(k -> k.type() == type).count();
            int predictedCount = (int) predicted.stream().filter(k -> k.type() == type).count();
            int correct = (int) predicted.stream().filter(k -> k.type() == type && truth.contains(k)).count();
            byType.put(type, new TypeScore(labelled, predictedCount, correct));
        }

        Map<String, Match> matchByConfirm = new HashMap<>();
        Set<String> matchedTrades = new HashSet<>();
        for (Match m : result.matches()) {
            matchByConfirm.put(m.externalRef(), m);
            matchedTrades.addAll(m.internalRefs());
        }

        int noise = 0;
        int noiseFalse = 0;
        int clean = 0;
        int cleanFalse = 0;
        int paired = 0;
        int idealStp = 0;
        for (Label label : labels) {
            if (label.isBreak()) {
                continue;
            }
            clean++;
            idealStp += label.tradeRefs().size();
            boolean falseBreak = casesWithBreaks.contains(label.caseId());
            if (falseBreak) {
                cleanFalse++;
            }
            if (label.noise() != null) {
                noise++;
                if (falseBreak) {
                    noiseFalse++;
                }
            }
            Match m = matchByConfirm.get(label.brokerRefs().getFirst());
            if (m != null && new HashSet<>(m.internalRefs()).equals(new HashSet<>(label.tradeRefs()))) {
                paired++;
            }
        }

        int stp = (int) matchedTrades.stream().filter(ref -> !refsInBreaks.contains(ref)).count();
        List<String> falsePositives = predicted.stream().filter(k -> !truth.contains(k)).map(Key::toString).sorted().toList();
        List<String> falseNegatives = truth.stream().filter(k -> !predicted.contains(k)).map(Key::toString).sorted().toList();
        return new Scorecard(byType, noise, noiseFalse, clean, cleanFalse, paired, internalTrades, stp, idealStp,
                falsePositives, falseNegatives);
    }

    public TypeScore overall() {
        int labelled = 0;
        int predicted = 0;
        int correct = 0;
        for (TypeScore s : byType.values()) {
            labelled += s.labelled();
            predicted += s.predicted();
            correct += s.correct();
        }
        return new TypeScore(labelled, predicted, correct);
    }

    public double noiseFalseBreakRate() {
        return noiseCases == 0 ? 0 : (double) noiseCasesWithFalseBreaks / noiseCases;
    }

    public double cleanFalseBreakRate() {
        return cleanCases == 0 ? 0 : (double) cleanCasesWithFalseBreaks / cleanCases;
    }

    public double stpRate() {
        return internalTrades == 0 ? 0 : (double) straightThroughTrades / internalTrades;
    }

    public double idealStpRate() {
        return internalTrades == 0 ? 0 : (double) idealStraightThroughTrades / internalTrades;
    }
}
