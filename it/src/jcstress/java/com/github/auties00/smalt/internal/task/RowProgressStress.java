package com.github.auties00.smalt.internal.task;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The wavefront tracker of {@link RowProgress}: a row's progress publishes what the row wrote before it, a waiting
 * row wakes when the row it waits on advances, and a failure releases it.
 */
public final class RowProgressStress {
    private RowProgressStress() {
    }

    @JCStressTest
    @Outcome(id = "1, 42", expect = Expect.ACCEPTABLE, desc = "the waiter saw the progress and the data before it")
    @Outcome(expect = Expect.FORBIDDEN, desc = "the progress was seen without the data written before it")
    @State
    public static class Publication {
        private final RowProgress progress = new RowProgress();
        private int data;

        public Publication() {
            progress.begin(2);
        }

        @Actor
        public void writer() {
            data = 42;
            progress.advance(0, 1);
        }

        @Actor
        public void reader(II_Result result) {
            result.r1 = progress.await(0, 1) ? 1 : 0;
            result.r2 = data;
        }
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the waiting row woke up when the row advanced")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the waiting row missed the advance")
    @State
    public static class Wakeup {
        private final RowProgress progress = new RowProgress();

        public Wakeup() {
            progress.begin(1);
        }

        @Actor
        public void waiter() {
            progress.await(0, 1);
        }

        @Signal
        public void advance() {
            progress.advance(0, 1);
        }
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the failure released the waiting row")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the waiting row missed the failure")
    @State
    public static class Failure {
        private final RowProgress progress = new RowProgress();

        public Failure() {
            progress.begin(1);
        }

        @Actor
        public void waiter() {
            progress.await(0, 1);
        }

        @Signal
        public void fail() {
            progress.fail();
        }
    }
}
