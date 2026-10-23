package com.recon.core.match;

import static com.recon.core.match.Fixtures.confirmFor;
import static com.recon.core.match.Fixtures.confirmForBlock;
import static com.recon.core.match.Fixtures.fullMatcher;
import static org.assertj.core.api.Assertions.assertThat;

import com.recon.core.model.ExternalConfirm;
import com.recon.core.model.InternalTrade;
import com.recon.core.model.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants that must hold for any input, not just generator-shaped data. The random books are
 * drawn from a deliberately tiny universe (2 ISINs, 2 counterparties, 3 quantities, 3 prices) so
 * many records collide and the passes have to compete for them.
 */
class MatcherPropertiesTest {

    record Book(List<InternalTrade> trades, List<ExternalConfirm> confirms) {
    }

    @Property(tries = 300)
    void noRecordAppearsInTwoMatches(@ForAll @IntRange(max = 40) int cases, @ForAll long seed) {
        MatchResult result = fullMatcher().match(randomBook(cases, seed).trades(), randomBook(cases, seed).confirms());

        List<String> refs = new ArrayList<>();
        for (Match m : result.matches()) {
            refs.addAll(m.internalRefs());
            refs.add(m.externalRef());
        }
        assertThat(refs).doesNotHaveDuplicates();
    }

    @Property(tries = 300)
    void everyRecordIsMatchedOrInABreak(@ForAll @IntRange(max = 40) int cases, @ForAll long seed) {
        Book book = randomBook(cases, seed);
        MatchResult result = fullMatcher().match(book.trades(), book.confirms());

        Set<String> accounted = new HashSet<>(result.matchedRefs());
        for (Break b : result.breaks()) {
            if (b.internalRef() != null) {
                accounted.add(b.internalRef());
            }
            if (b.externalRef() != null) {
                accounted.add(b.externalRef());
            }
        }
        book.trades().forEach(t -> assertThat(accounted).contains(t.tradeRef()));
        book.confirms().forEach(c -> assertThat(accounted).contains(c.brokerRef()));
    }

    @Property(tries = 300)
    void resultDoesNotDependOnInputOrder(@ForAll @IntRange(max = 40) int cases, @ForAll long seed, @ForAll long shuffleSeed) {
        Book book = randomBook(cases, seed);
        List<InternalTrade> trades = new ArrayList<>(book.trades());
        List<ExternalConfirm> confirms = new ArrayList<>(book.confirms());
        Collections.shuffle(trades, new Random(shuffleSeed));
        Collections.shuffle(confirms, new Random(shuffleSeed + 1));

        assertThat(fullMatcher().match(trades, confirms)).isEqualTo(fullMatcher().match(book.trades(), book.confirms()));
    }

    @Property(tries = 100)
    void baselineNeverFindsMoreMatchesThanFullMatcher(@ForAll @IntRange(max = 40) int cases, @ForAll long seed) {
        Book book = randomBook(cases, seed);

        int full = fullMatcher().match(book.trades(), book.confirms()).matches().size();
        int baseline = Matcher.baseline(Fixtures.STATIC_DATA, Fixtures.TOLERANCES).match(book.trades(), book.confirms()).matches().size();

        assertThat(baseline).isLessThanOrEqualTo(full);
    }

    /** A random mix of clean pairs, field breaks, missing, unexpected, duplicate and block cases. */
    static Book randomBook(int cases, long seed) {
        Random rng = new Random(seed);
        String[] isins = {Fixtures.APPLE, Fixtures.BOND};
        String[] counterparties = {Fixtures.ALPHA, Fixtures.BETA};
        String[] quantities = {"100", "200", "300"};
        String[] prices = {"100.00", "100.01", "101.00"};
        List<InternalTrade> trades = new ArrayList<>();
        List<ExternalConfirm> confirms = new ArrayList<>();
        int ref = 0;

        for (int i = 0; i < cases; i++) {
            Fixtures.TradeBuilder builder = Fixtures.trade("T" + (++ref))
                    .isin(isins[rng.nextInt(2)])
                    .side(rng.nextBoolean() ? Side.BUY : Side.SELL)
                    .counterparty(counterparties[rng.nextInt(2)])
                    .quantity(quantities[rng.nextInt(3)])
                    .price(prices[rng.nextInt(3)]);
            int kind = rng.nextInt(7);
            if (kind == 6) {
                // A block of 2-3 allocations confirmed once.
                String block = "BLK" + ref;
                List<InternalTrade> allocations = new ArrayList<>();
                for (int a = 0, n = 2 + rng.nextInt(2); a < n; a++) {
                    allocations.add(builder.block(block).account("ACC" + a).build());
                    builder = copyOf(allocations.getLast(), "T" + (++ref));
                }
                trades.addAll(allocations);
                confirms.add(confirmForBlock(allocations, "C" + ref).build());
                continue;
            }
            InternalTrade trade = builder.build();
            Fixtures.ConfirmBuilder confirm = confirmFor(trade, "C" + ref);
            switch (kind) {
                case 0 -> trades.add(trade);                                       // missing confirm
                case 1 -> confirms.add(confirm.build());                           // unexpected confirm
                case 2 -> {                                                        // duplicate
                    trades.add(trade);
                    confirms.add(confirm.build());
                    confirms.add(confirmFor(trade, "D" + ref).build());
                }
                case 3 -> {                                                        // one field wrong
                    trades.add(trade);
                    confirms.add(switch (rng.nextInt(4)) {
                        case 0 -> confirm.quantity("999").build();
                        case 1 -> confirm.side(trade.side().opposite()).build();
                        case 2 -> confirm.counterparty(trade.counterparty().equals(Fixtures.ALPHA) ? Fixtures.BETA : Fixtures.ALPHA).build();
                        default -> confirm.price("150.00").build();
                    });
                }
                default -> {                                                       // clean
                    trades.add(trade);
                    confirms.add(confirm.build());
                }
            }
        }
        return new Book(trades, confirms);
    }

    private static Fixtures.TradeBuilder copyOf(InternalTrade t, String ref) {
        return Fixtures.trade(ref).isin(t.isin()).side(t.side()).counterparty(t.counterparty())
                .quantity(t.quantity().toPlainString()).price(t.price().toPlainString()).block(t.blockRef());
    }
}
