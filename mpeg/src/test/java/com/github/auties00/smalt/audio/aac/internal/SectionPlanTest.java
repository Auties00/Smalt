package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the linear-time section planner against the direct search over every section start and
 * codebook, including its tie-breaking, on random cost tables with forbidden codebooks, all-zero
 * bands and many ties.
 */
class SectionPlanTest {
    // The direct search: extend each section backwards, drop a codebook once a band forbids it, keep the first
    // strictly better candidate in (start descending, codebook ascending) order.
    private static int reference(int[] costs, int bands, boolean shortWindow, int[] books) {
        var best = new int[65];
        var from = new int[65];
        var bookAt = new int[65];
        var running = new int[SpectralCoder.BOOKS];
        for (var end = 1; end <= bands; end++) {
            var bestCost = Integer.MAX_VALUE;
            var bestStart = 0;
            var bestBook = 0;
            var alive = (1 << SpectralCoder.BOOKS) - 1;
            Arrays.fill(running, 0);
            for (var start = end - 1; start >= 0 && alive != 0; start--) {
                var base = start * SpectralCoder.BOOKS;
                var zero = costs[base] == 0 ? 1 : 0;
                var prefix = SpectralCoder.sectionHeaderBits(end - start, shortWindow) + best[start];
                for (var remaining = alive; remaining != 0; remaining &= remaining - 1) {
                    var book = Integer.numberOfTrailingZeros(remaining);
                    var cost = costs[base + book];
                    if (cost >= SpectralCoder.FORBIDDEN) {
                        alive &= ~(1 << book);
                        continue;
                    }
                    var total = running[book] + cost + (book != 0 ? zero : 0);
                    running[book] = total;
                    if (prefix + total < bestCost) {
                        bestCost = prefix + total;
                        bestStart = start;
                        bestBook = book;
                    }
                }
            }
            best[end] = bestCost;
            from[end] = bestStart;
            bookAt[end] = bestBook;
        }
        var end = bands;
        while (end > 0) {
            var start = from[end];
            for (var band = start; band < end; band++) {
                books[band] = bookAt[end];
            }
            end = start;
        }
        return best[bands];
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("chooses the same sections and total as the direct search")
    void matchesDirectSearch(int seed) {
        var random = new Random(seed);
        var coder = new SpectralCoder();
        for (var trial = 0; trial < 4000; trial++) {
            var shortWindow = random.nextBoolean();
            var bands = 1 + random.nextInt(shortWindow ? 15 : 64);
            var costs = new int[bands * SpectralCoder.BOOKS];
            var range = 1 + random.nextInt(trial % 3 == 0 ? 4 : 60);
            for (var band = 0; band < bands; band++) {
                var allZero = random.nextInt(4) == 0;
                for (var book = 0; book < SpectralCoder.BOOKS; book++) {
                    int cost;
                    if (book == 0) {
                        cost = allZero ? 0 : SpectralCoder.FORBIDDEN;
                    } else if (book < Codebooks.ESC_HCB && random.nextInt(5) == 0) {
                        cost = SpectralCoder.FORBIDDEN;
                    } else {
                        cost = random.nextInt(range);
                    }
                    costs[band * SpectralCoder.BOOKS + book] = cost;
                }
            }
            var expected = new int[bands];
            var actual = new int[bands];
            var total = reference(costs, bands, shortWindow, expected);
            assertEquals(total, coder.plan(costs, bands, shortWindow, actual), "total of trial " + trial);
            assertArrayEquals(expected, actual, "books of trial " + trial);
        }
    }
}
