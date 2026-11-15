package com.github.auties00.smalt.internal.task;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Two callers running batches of bands on the shared helpers at once: every band of each batch runs exactly once,
 * and the caller sees every write of its bands when {@link ParallelBands#run} returns. Runs with
 * {@code smalt.threads=4}, so helpers claim bands of both batches.
 */
@JCStressTest
@Outcome(id = "8, 8", expect = Expect.ACCEPTABLE, desc = "each caller sees its 8 bands, each run once")
@Outcome(expect = Expect.FORBIDDEN, desc = "a band ran twice or never, or its write is not visible")
@State
public class BandsStress {
    private final int[] first = new int[8];
    private final int[] second = new int[8];

    @Actor
    public void firstCaller(II_Result result) {
        ParallelBands.run(first.length, index -> first[index]++);
        result.r1 = sum(first);
    }

    @Actor
    public void secondCaller(II_Result result) {
        ParallelBands.run(second.length, index -> second[index]++);
        result.r2 = sum(second);
    }

    private static int sum(int[] values) {
        var sum = 0;
        for (var value : values) {
            sum += value == 1 ? 1 : 100;
        }
        return sum;
    }
}
