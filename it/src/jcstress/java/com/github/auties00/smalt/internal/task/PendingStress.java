package com.github.auties00.smalt.internal.task;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The races of a {@link ParallelBands.Pending} computation: a thread claiming it against one canceling it, one
 * joining it, and one canceling it and then waiting for it. The forked JVMs run with {@code smalt.threads=1}, so no
 * helper claims the computation and only the two actors race.
 */
public final class PendingStress {
    private PendingStress() {
    }

    @JCStressTest
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "the computation ran, so it could not be canceled")
    @Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "the computation was canceled before it started")
    @Outcome(expect = Expect.FORBIDDEN, desc = "ran and canceled, or neither")
    @State
    public static class RunOrCancel {
        private final AtomicInteger runs = new AtomicInteger();
        private final ParallelBands.Pending<Integer> pending = ParallelBands.submit(runs::incrementAndGet);

        @Actor
        public void run(II_Result result) {
            result.r1 = pending.runOne() ? 1 : 0;
        }

        @Actor
        public void cancel(II_Result result) {
            result.r2 = pending.tryCancel() ? 1 : 0;
        }
    }

    @JCStressTest
    @Outcome(id = "42, 1, 1", expect = Expect.ACCEPTABLE, desc = "the result is seen, and the computation ran once")
    @Outcome(expect = Expect.FORBIDDEN, desc = "a lost result or a computation that ran twice or never")
    @State
    public static class RunAndJoin {
        private final AtomicInteger runs = new AtomicInteger();
        private final ParallelBands.Pending<Integer> pending = ParallelBands.submit(() -> {
            runs.incrementAndGet();
            return 42;
        });

        @Actor
        public void run() {
            pending.runOne();
        }

        @Actor
        public void join(III_Result result) {
            result.r1 = pending.join();
        }

        @Arbiter
        public void arbiter(III_Result result) {
            result.r2 = runs.get();
            result.r3 = pending.started() ? 1 : 0;
        }
    }

    @JCStressTest
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "cancel came late and waited for the computation")
    @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "cancel came first and the computation never ran")
    @Outcome(expect = Expect.FORBIDDEN, desc = "cancel returned while the computation was still running, or ran it")
    @State
    public static class CancelWaits {
        private int written;
        private final ParallelBands.Pending<Void> pending = ParallelBands.start(() -> written = 1);

        @Actor
        public void run(II_Result result) {
            result.r1 = pending.runOne() ? 1 : 0;
        }

        @Actor
        public void cancel(II_Result result) {
            pending.cancel();
            result.r2 = written;
        }
    }
}
